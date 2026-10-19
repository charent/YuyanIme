package com.yuyan.inputmethod.correction

import android.os.SystemClock
import android.util.Log
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.PinyinCorrectionStrength
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.core.Rime

/**
 * 纠错会话：触发判定 + 影子查询 + 结果合并。
 *
 * 设计文档 4.2（触发策略）与 4.4（Rime 二次验证）的落地：
 *
 * | 触发 | 条件 | 放置 |
 * | --- | --- | --- |
 * | 硬触发 | Rime 一个真实候选都没有（只剩编码回声） | 候选栏前部（此时前面本来就没东西） |
 * | 软触发 | 首个候选的拼音注释覆盖不到整串输入，或存在「双写折叠」这类高精度假设 | 追加到尾部 |
 * | 模糊音 | 输入完全合法、且用户显式开启 | 追加到尾部 |
 *
 * 影子查询的实现方式（[probe]）：用 `replaceRimeKey` 把整串编码换成纠正后的编码、取一次候选、
 * 再立刻换回原串。**任何路径都必须还原成功**，否则宁可让本次进程的纠错整体降级（[degraded]），
 * 也不能让用户看到错乱的拼音。
 */
object CorrectionSession {

    private const val TAG = "PinyinCorrection"

    private const val MAX_CACHE = 32

    /** 硬触发最多验证几个编码、总共给几条候选 */
    private const val HARD_CODES = 3
    private const val HARD_ITEMS = 5

    /**
     * 软触发最多验证三个编码、总共给三条候选。假设是按「音节数分组轮流取」排的，
     * 同一个代价档里可能夹着别的切分（`xung` 的 `xun` / `xun'ga` / `xing`），
     * 只查两个会漏掉真正的目标。
     */
    private const val SOFT_CODES = 3
    private const val SOFT_ITEMS = 3

    /** 模糊音一次只扩展一个变体，避免候选爆炸和无谓的翻译开销 */
    private const val FUZZY_VARIANTS = 1
    private const val FUZZY_ITEMS = 2

    /** 近键变体：生成上限 / 探测上限 / 展示上限 / 探测时间预算 */
    private const val NEAR_KEY_VARIANTS = 8
    private const val NEAR_KEY_PROBES = 8
    private const val NEAR_KEY_ITEMS = 2
    private const val NEAR_KEY_BUDGET_MS = 60L

    /** 影子查询结果缓存：同一个变体编码在相邻几次按键里会反复出现，命中后不再碰引擎 */
    private const val MAX_PROBE_CACHE = 64

    /** 纠错候选插在哪里 */
    enum class Placement {
        /** 置于候选栏最前：Rime 一个真实候选都没有时 */
        FRONT,

        /** 插在首个候选之后：不影响原来的首位，又能被看到 */
        AFTER_FIRST,

        /** 追加到最后：合法输入的模糊音扩展只能靠后 */
        TAIL,
    }

    /**
     * 一次纠错的结果。
     * @param texts 纠正后验证通过的候选词（按可信度排序）
     * @param placement 插入位置
     */
    data class Result(val texts: List<String>, val placement: Placement)

    /** 本次纠错的原始编码，用于把「打错的码 -> 正确词」记进用户词库 */
    var lastRawCode: String = ""
        private set

    /** 还原失败后整体降级：宁可没有纠错，也不能污染用户的组合串 */
    @Volatile
    var degraded: Boolean = false
        private set

    private val cache = object : LinkedHashMap<String, Any>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>): Boolean =
            size > MAX_CACHE
    }

    /** 影子查询结果缓存（含"查不到"）：变体编码在相邻按键里高度重复，能省掉大部分引擎往返 */
    private val probeCache = object : LinkedHashMap<String, Any>(MAX_PROBE_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>): Boolean =
            size > MAX_PROBE_CACHE
    }

    private val EMPTY = Any()

    fun reset() {
        cache.clear()
        probeCache.clear()
        lastRawCode = ""
    }

    /**
     * @param composition 当前组合串（原始 preedit，可能含分隔符）
     * @param rimeCandidates Rime 给出的原始候选（含编码回声）
     * @param existingTexts 展示列表里已有的候选文本，用于去重
     * @param originalSupport 当前编码在词库里的候选总数（词库支持度）
     */
    fun correct(
        composition: String,
        rimeCandidates: List<CandidateListItem>,
        existingTexts: Collection<String>,
        originalSupport: Int = 0,
    ): Result? {
        if (degraded) return null

        val prefs = AppPrefs.getInstance()
        if (!prefs.input.pinyinCorrectionEnable.getValue()) return null
        // 九键靠候选消歧、双拼的编码不是拼音、英文方案没有拼音语义：都不接入
        if (Rime.getCurrentRimeSchema() != CustomConstant.SCHEMA_ZH_QWERTY) return null
        if (composition.isEmpty() || composition.endsWith("'")) return null

        val raw = PinyinCorrector.normalize(composition)
        if (raw.length < 2 || raw.length > 24 || raw.any { it !in 'a'..'z' }) return null

        cache[raw]?.let { cached ->
            return if (cached === EMPTY) null else cached as Result
        }

        val result = evaluate(raw, composition, rimeCandidates, existingTexts, originalSupport)
        cache[raw] = result ?: EMPTY
        if (result != null && result.texts.isNotEmpty()) lastRawCode = raw
        return result
    }

    // ---------------------------------------------------------------- 触发判定

    /**
     * 两条路径：
     *
     * 1. **切不开**（[PinyinCorrector.hasCleanParse] 为 false）：真打错了，生成 Top-N 纠错编码去验证；
     * 2. **切得开**：正常输入，但最后一个音节里可能有**近键误按**（`zhonggui` 的 i 打成 o 就是
     *    `zhongguo`），所以还会试一次「只换末尾音节里的一个元音」的变体。
     *
     * 为什么不用「Rime 有没有候选 / 拼音注释覆盖不覆盖全」判定：Rime 会把 `zhonguo` 切成
     * `zhong+u+o`、`yiuxi` 切成 `yi+u+xi`，照样给出「中欧」「以」，那两条信号恒为正常。
     */
    private fun evaluate(
        raw: String,
        composition: String,
        rimeCandidates: List<CandidateListItem>,
        existingTexts: Collection<String>,
        originalSupport: Int,
    ): Result? {
        val prefs = AppPrefs.getInstance()
        val realCandidates = rimeCandidates.filter { !isEcho(it.text, composition) }

        if (PinyinCorrector.hasCleanParse(raw)) {
            if (raw.length < 3) return null
            // 近键误按：穷尽生成「换一个相邻键」的合法变体，用词频 + 词库验证并排序
            val near = buildNearKey(raw, originalSupport, realCandidates.firstOrNull()?.text.orEmpty())
            if (near != null) {
                Log.d(TAG, "near-key raw=$raw -> ${near.texts}")
                return near
            }
            // 模糊音是纯召回扩展，默认关，结果只放最后
            if (prefs.input.pinyinFuzzyEnable.getValue() && realCandidates.isNotEmpty()) {
                val fuzzy = buildFuzzy(raw, existingTexts)
                if (fuzzy.isNotEmpty()) {
                    Log.d(TAG, "fuzzy raw=$raw -> $fuzzy")
                    return Result(fuzzy, Placement.TAIL)
                }
            }
            return null
        }

        // 1~2 个字母太少，`z`、`zh` 这种可能只是还没打完；
        // `zhon` 是 `zhong` 的前缀，也先不打扰
        if (raw.length < 3) return null
        if (PinyinCorrector.isPrefixOfLongerSyllable(raw)) return null

        val budget = prefs.input.pinyinCorrectionStrength.getValue().budget()
        val hypotheses = PinyinCorrector.decode(raw, budget).filter { it.compactCode != raw }
        if (hypotheses.isEmpty()) return null

        // Rime 一个真实候选都没有 -> 置前（前面本来就没东西）；有候选 -> 插在首个候选之后，
        // 既让纠错结果看得见，又不改变原来的首位
        val hard = realCandidates.isEmpty()
        val placement = if (hard) Placement.FRONT else Placement.AFTER_FIRST
        val codes = if (hard) HARD_CODES else SOFT_CODES
        val items = if (hard) HARD_ITEMS else SOFT_ITEMS
        val firstShare = if (hard) 3 else 2

        val result = build(hypotheses.take(codes), items, firstShare)
            ?.let { Result(it, placement) }
        if (result != null) {
            Log.d(TAG, "trigger raw=$raw placement=$placement -> ${result.texts}")
        }
        return result
    }

    /**
     * 近键误按：合法输入里，某个字母可能是按到了旁边的键（`bihao` -> `nihao`）。
     *
     * 生成阶段是穷尽的（每个字母 × 每个相邻键），但影子查询有预算，所以：
     * 1. 先按「声母位置 / 左右相邻 / 靠后音节」排个便宜的序，最多探测 [NEAR_KEY_PROBES] 个；
     * 2. 总耗时超过 [NEAR_KEY_BUDGET_MS] 就提前收手；
     * 3. **只提示「比你打出来的那个词更常用」的变体**：有词频表就比词频（`你好 725` 远高于
     *    `比高 0`），词频表没就绪时退回比词库支持度（候选总数）；
     * 4. 第一候选必须是「至少两个汉字」的成词，按词频降序、支持度兜底取前 [NEAR_KEY_ITEMS] 条。
     *
     * 于是 `bihao` 只会给出 `你好`（`比要/比高` 词频为 0 被过滤），
     * `zhongguo` 这种本来就很常用的输入也不会因为 `中火` 有候选就冒出建议。
     */
    private fun buildNearKey(raw: String, originalSupport: Int, originalWord: String): Result? {
        val variants = PinyinCorrector.nearKeyVariants(raw, NEAR_KEY_VARIANTS)
        if (variants.isEmpty()) return null

        val useFrequency = WordFrequency.isReady
        // 当前首位候选是单字时（说明这个词根本没成词）不能拿它的词频当基准：
        // 「比」这种单字词频很高，会把「你好」这类真正的词压下去
        val baseFrequency = if (cjkCount(originalWord) >= 2) WordFrequency.freq(originalWord) else 0

        val started = SystemClock.elapsedRealtime()
        // (文本, 词频, 词库支持度)
        val hits = ArrayList<Triple<String, Int, Int>>(NEAR_KEY_PROBES)
        for (variant in variants.take(NEAR_KEY_PROBES)) {
            if (SystemClock.elapsedRealtime() - started > NEAR_KEY_BUDGET_MS) break
            val probe = probe(variant.code) ?: continue
            val word = probe.texts.firstOrNull { cjkCount(it) >= 2 } ?: continue
            val frequency = WordFrequency.freq(word)
            val stronger = if (useFrequency) frequency > baseFrequency else probe.support > originalSupport
            if (!stronger) continue
            hits.add(Triple(word, frequency, probe.support))
        }
        if (hits.isEmpty()) return null

        val texts = hits
            .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third })
            .map { it.first }
            .distinct()
            .take(NEAR_KEY_ITEMS)
        return Result(texts, Placement.AFTER_FIRST)
    }

    // ---------------------------------------------------------------- 影子查询

    /** 一次影子查询的结果：候选文本 + 该编码在词库里的支持度（候选总数） */
    private class ProbeData(val texts: List<String>, val support: Int)

    /**
     * 依次验证纠正后的编码，取回候选词。第一个编码多取几条（它最可能是正确答案），后续各取一条。
     *
     * 最后要过一遍**词频闸**：Rime 会把「辣+死+公」拼成 `辣死公` 这种谁都不用的组合，
     * 这类词在词表里查不到（freq=0）。词表就绪时只保留查得到的词；
     * 万一全被滤掉（目标本身就是生僻词），退回未过滤的结果，宁可多给一条也不空手。
     */
    private fun build(
        hyps: List<PinyinCorrector.Hypothesis>,
        limit: Int,
        firstShare: Int,
    ): List<String>? {
        if (hyps.isEmpty()) return null
        val out = ArrayList<String>(limit * 2)
        for ((index, hypothesis) in hyps.withIndex()) {
            val share = if (index == 0) firstShare else 1
            var taken = 0
            for (text in probe(hypothesis.code)?.texts.orEmpty()) {
                if (taken >= share) break
                // 回声项（编码本身）、英文、emoji 都不是纠错的目标
                if (cjkCount(text) == 0) continue
                if (out.any { it == text }) continue
                out.add(text)
                taken++
            }
        }
        if (out.isEmpty()) return null

        if (!WordFrequency.isReady) return out.take(limit)

        // 词频闸：丢掉词表里没有的组合（「辣死公」这种由音节硬拼出来的），
        // 排序规则是「成词优先 > 词频高 > 原代价顺序」——用户打了两三个音节时，
        // 多字词比单字更像目标；只有单音节输入才会变成纯词频比较。
        val known = out.filter { WordFrequency.freq(it) > 0 }
        val pool = if (known.isEmpty()) out else known
        return pool
            .sortedWith(
                compareByDescending<String> { if (cjkCount(it) >= 2) 1 else 0 }
                    .thenByDescending { WordFrequency.freq(it) }
            )
            .take(limit)
            .ifEmpty { null }
    }

    private fun buildFuzzy(raw: String, existingTexts: Collection<String>): List<String> {
        val seen = HashSet<String>(existingTexts.size + FUZZY_ITEMS * 2)
        existingTexts.forEach { seen.add(it.lowercase()) }
        val out = ArrayList<String>(FUZZY_ITEMS)
        for (variant in PinyinCorrector.fuzzyVariants(raw, FUZZY_VARIANTS)) {
            for (text in probe(variant)?.texts.orEmpty()) {
                if (out.size >= FUZZY_ITEMS) break
                if (!isUsableCandidate(text, variant)) continue
                if (!seen.add(text.lowercase())) continue
                out.add(text)
            }
        }
        return out
    }

    /**
     * 用纠正后的编码做一次影子查询，拿完候选立刻还原组合串。
     * `replaceRimeKey(caret, len, key)` 的语义是「把组合串的 [caret, caret+len) 换成 key」，
     * 全串替换就是 `(0, 全串长度)`；这条链路 `KeyRecordStack.replacePinyinWithT9Keys` 已经在用。
     * 「清空重放」只作为还原组合串时的兜底（成本高：最多 24 次词典查询），不再参与探测。
     * 结果（含查不到）按编码缓存，相邻按键里同一个变体编码反复出现时不必再碰引擎。
     */
    private fun probe(code: String): ProbeData? {
        val cached = probeCache[code]
        if (cached != null) return if (cached === EMPTY) null else cached as ProbeData

        val saved = Rime.compositionText
        if (saved.isEmpty()) return null

        // 两条路都要留：组合串带自动分隔符时（l's'o'gong），整串替换 replaceKey 会换不过去，
        // 只有「清空重放」能落到目标编码上——去掉它纠错会整块失效（实测 2026-10-19 23:55）。
        // 重放最多 24 次词典查询，所以纠错已改成停顿后再算（RimeEngine.scheduleCorrection），不占按键路径。
        val queried = replaceComposition(code) || replayComposition(code)
        if (!queried) {
            // 两条路都没能把组合串换成目标编码：还原成用户原来的输入，放弃这次验证
            Log.w(TAG, "probe failed: composition=$code unfetchable, saved=$saved")
            restoreComposition(saved)
            return null
        }

        val texts = try {
            val context = Rime.getRimeContext()
            val all = context?.candidates.orEmpty()
            // 只认「拼音注释就是这个编码」的候选：拿回上一次查询的残留菜单时，注释会对不上，
            // 直接丢掉，避免把「稍 / 算」这种无关词当成纠错结果（beijingg 的实际踩坑）
            val matched = all.filter { letters(it.comment) == letters(code) }
            if (matched.isEmpty() && all.isNotEmpty()) {
                Log.d(TAG, "probe $code: comments mismatch ${all.take(2).map { it.comment }}")
            }
            // 候选总数当作「词库支持度」：常见编码的候选明显更多
            ProbeData(matched.map { it.text }, context?.menu?.numCandidates ?: all.size)
        } catch (t: Throwable) {
            Log.w(TAG, "probe $code failed: ${t.message}")
            null
        } finally {
            restoreComposition(saved)
        }
        // 只缓存「查成功」的结果：查询本身失败（换不过去 / 抛异常）是临时状态，缓存了会让这条编码再也不纠
        if (texts != null) probeCache[code] = texts
        return texts
    }

    /** 一次 replaceKey 把整串换成 [code]，并校验真的换成了（preedit 里可能带自动分隔符） */
    private fun replaceComposition(code: String): Boolean {
        val live = Rime.compositionText
        if (live.isEmpty()) return false
        if (Rime.replaceKey(0, live.length, code) && compact(Rime.compositionText) == compact(code)) {
            return true
        }
        // preedit 里可能带 Rime 自动补的分隔符，和真实输入长度不一致，退一步用组合串长度
        val inputLength = Rime.composition?.length ?: 0
        return inputLength > 0 && inputLength != live.length &&
            Rime.replaceKey(0, inputLength, code) && compact(Rime.compositionText) == compact(code)
    }

    /** 清空后按字符重放编码（≤24 个字母，成本可控） */
    private fun replayComposition(code: String): Boolean {
        Rime.clearComposition()
        code.forEach { ch -> if (ch in 'a'..'z') Rime.processKey(ch.code, 0) }
        return compact(Rime.compositionText) == compact(code)
    }

    /**
     * 还原组合串。先试 replaceKey，失败就退化成「清空 + 按原串重放」。
     * 两条路都失败时清空组合串并让纠错整体降级——用户顶多重打一次，不会看到错乱的拼音。
     */
    private fun restoreComposition(saved: String) {
        if (compact(Rime.compositionText) == compact(saved)) return
        if (replaceComposition(saved)) return
        if (replayComposition(saved)) return

        degraded = true
        Log.e(TAG, "restore failed, correction disabled. saved=$saved live=${Rime.compositionText}")
        Rime.clearComposition()
    }

    // ---------------------------------------------------------------- 过滤

    private fun compact(text: String): String =
        text.replace("'", "").replace(" ", "").lowercase()

    /** 候选是不是「编码本身」的回声项 */
    private fun isEcho(text: String, composition: String): Boolean =
        compact(text).equals(compact(composition), ignoreCase = true)

    /** 只要含汉字的候选：回声项、英文、emoji 都不是纠错的目标 */
    private fun isUsableCandidate(text: String, code: String): Boolean {
        if (text.isBlank()) return false
        if (compact(text) == compact(code)) return false
        return cjkCount(text) > 0
    }

    private fun cjkCount(text: String): Int =
        text.count { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' }

    /** 拼音注释归一化：去掉声调、分隔符、大小写，只留 a-z（`zhōng'guó` -> `zhongguo`） */
    private fun letters(text: String): String = buildString(text.length) {
        text.lowercase().forEach { ch ->
            val base = if (ch in 'a'..'z') ch else BASE_LETTERS[ch] ?: return@forEach
            append(base)
        }
    }

    /** 常见带调拼音字母 -> 基础字母；没列到的声调字母直接丢弃 */
    private val BASE_LETTERS: Map<Char, Char> = buildMap {
        "āáǎà".forEach { put(it, 'a') }
        "ēéěè".forEach { put(it, 'e') }
        "īíǐì".forEach { put(it, 'i') }
        "ōóǒò".forEach { put(it, 'o') }
        "ūúǔù".forEach { put(it, 'u') }
        "ǖǘǚǜü".forEach { put(it, 'v') }
        put('ń', 'n'); put('ň', 'n'); put('ǹ', 'n')
        put('ḿ', 'm')
    }
}
