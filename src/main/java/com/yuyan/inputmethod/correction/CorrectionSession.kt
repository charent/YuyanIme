package com.yuyan.inputmethod.correction

import android.os.SystemClock
import android.util.Log
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.core.Rime
import java.util.Locale

/**
 * 纠错会话：生成候选编码 → 用引擎自己的词库裁决 → 只对胜出的编码取一次候选文本。
 *
 * ## 判定只依赖一件事：引擎词库里的权重
 *
 * `pinyin.table.bin`（[EngineLexicon]）就是引擎出候选用的词典，191 万条词条。
 * 「比好」「辣死公」这类音节硬拼的组合不在里面，「烂大街」「明矾」这类口语词反而有，
 * 所以不需要词频表、搭配统计或任何近似器：
 *
 * ```
 * 变体胜出 ⟺ 变体在词库中 且
 *            ( 原输入不在词库 ? 变体权重 ≥ MIN_KNOWN_WEIGHT
 *                             : 变体权重 ≥ 原权重 + WEIGHT_MARGIN )
 * ```
 *
 * 两个常数的依据见 `docs/pinyin-correction-redesign.md` §2.2：
 * 11 个实测 case 全中、2000 个高频编码反例零误报，且在 3.0~4.5 区间完全不敏感。
 *
 * ## 两条生成路径
 *
 * | 输入 | 生成 | 放置 |
 * | --- | --- | --- |
 * | 切得开（`bihao`、`lanfajie`） | 穷尽的**单键相邻替换**变体 | 首位之后 |
 * | 切不开（`zhonguo`、`xung`） | 音节级编辑距离假设 | 无候选时置前，否则首位之后 |
 *
 * ## 影子查询只负责取词
 *
 * 判断在内存里完成（0.2 ms），[probe] 只在**已经胜出**的 1~3 个编码上跑，
 * 拿回候选文本后立刻还原组合串。查询失败只是少一条文本，不影响判断；
 * 还原失败才会让本次进程整体降级（[degraded]），绝不把组合串留在中间态。
 */
object CorrectionSession {

    private const val TAG = "PinyinCorrection"

    // ---------------------------------------------------------------- 判定常数

    /** 原输入不在词库时，变体至少要有的权重（词库默认档约 4.6~5.0） */
    private const val MIN_KNOWN_WEIGHT = 4.0f

    /**
     * 原输入也在词库时，变体要比它高出的幅度。
     *
     * 权重不是纯词频（190 万条里 139 万条落在默认档 5.0），所以不做绝对比较，
     * 只认「明显更好」：`bihao→nihao` 差 3.9、`zhonggui→zhongguo` 差 3.6、
     * `mingfan→mingdan` 差 3.6，都在 3.0 之上；而 `shei→zhei` 这类 2.8 的就被挡住。
     */
    private const val WEIGHT_MARGIN = 3.0f

    /**
     * 只保留与首选权重相差不超过这个幅度的变体。
     *
     * 没有这一条时，`lsogong` 除了「老公」(12.8) 还会带出「四公」(7.4)——词库里确实存在，
     * 但用户根本不会想打它。1.5 只挡掉「明显不是一个量级」的，同档位的近音词（
     * `haoo` 的 好 15.8 / 还 15.7 / 高 14.8）仍然全部保留，让用户自己挑。
     */
    private const val RELATIVE_MARGIN = 1.5f

    // ---------------------------------------------------------------- 生成预算

    /** 近键变体最多生成多少个（判定是纯内存的，可以放宽） */
    private const val NEAR_KEY_VARIANTS = 12

    /** 非法输入最多取几个假设去裁决 */
    private const val ILLEGAL_VARIANTS = 6

    /** 两类路径各自最多展示几条纠错候选 */
    private const val MAX_ITEMS = 3

    /** 单个变体最多贡献几条文本（第一个胜出编码通常最准） */
    private const val TEXTS_PER_VARIANT = 2

    /** 模糊音是纯召回扩展，默认关闭，结果只追加在最后 */
    private const val FUZZY_VARIANTS = 1
    private const val FUZZY_ITEMS = 2

    private const val MAX_CACHE = 32
    private const val MAX_PROBE_CACHE = 64

    /**
     * 影子查询一次取多少条候选。
     *
     * 原来直接读 `get_context` 返回的那一页（schema 的 `menu/page_size`，100 条）。
     * 候选改走全量列表后这里显式取同样的条数，保证「注释匹配编码」的召回范围不变。
     */
    private const val PROBE_CANDIDATES = 100

    /** 候选可信度缓存：一次输入会问很多遍同样的注释 */
    private const val VERIFIED_CACHE = 128

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
     * @param texts 纠正后验证通过的候选词（按权重降序）
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

    /** 影子查询结果缓存（含"查不到"）：相邻按键里同一个编码会反复出现 */
    private val probeCache = object : LinkedHashMap<String, Any>(MAX_PROBE_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>): Boolean =
            size > MAX_PROBE_CACHE
    }

    private val EMPTY = Any()

    /** 候选拼音注释 → 是否词库词条。候选栏每次重绘都要问一遍，缓存很值 */
    private val verifiedCache = object : LinkedHashMap<String, Boolean>(VERIFIED_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > VERIFIED_CACHE
    }

    fun reset() {
        cache.clear()
        probeCache.clear()
        verifiedCache.clear()
        lastRawCode = ""
    }

    /**
     * @param composition 当前组合串（原始 preedit，可能含分隔符）
     * @param rimeCandidates Rime 给出的原始候选（含编码回声）
     * @param existingTexts 展示列表里已有的候选文本，用于去重
     */
    fun correct(
        composition: String,
        rimeCandidates: List<CandidateListItem>,
        existingTexts: Collection<String>,
    ): Result? {
        if (degraded) return null
        // 词库没准备好（首次启动、文件缺失、格式不认识）：整体不提示，不做任何猜测
        val lexicon = EngineLexicon.shared() ?: return null

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

        val started = SystemClock.elapsedRealtime()
        val result = evaluate(raw, composition, rimeCandidates, existingTexts, lexicon)
        cache[raw] = result ?: EMPTY
        if (result != null && result.texts.isNotEmpty()) lastRawCode = raw
        if (result != null) {
            Log.d(TAG, "correct raw=$raw took=${SystemClock.elapsedRealtime() - started}ms")
        }
        return result
    }

    // ---------------------------------------------------------------- 裁决

    private fun evaluate(
        raw: String,
        composition: String,
        rimeCandidates: List<CandidateListItem>,
        existingTexts: Collection<String>,
        lexicon: EngineLexicon,
    ): Result? {
        val prefs = AppPrefs.getInstance()
        val realCandidates = rimeCandidates.filter { !isEcho(it.text, composition) }

        // 原输入的权重：切不开（真打错了）时它本来就不在词库里，按 0 处理。
        // 注意要试全部合法切分（`xian` 既可能是 `xian` 也可能是 `xi+an`），取最大值，
        // 否则会把「用户打对了的词」估低，凭空冒出纠错建议。
        val originalWeight = weightOfRaw(lexicon, raw)
        val clean = PinyinCorrector.hasCleanParse(raw)

        val variants = if (clean) {
            if (raw.length < 3) return null
            PinyinCorrector.nearKeyVariants(raw, NEAR_KEY_VARIANTS)
                .map { Variant(it.code, it.code.split('\''), it.score) }
        } else {
            // `z`、`zh` 这种可能只是还没打完；`zhon` 是 `zhong` 的前缀，也先不打扰
            if (raw.length < 3) return null
            if (PinyinCorrector.isPrefixOfLongerSyllable(raw)) return null
            val budget = prefs.input.pinyinCorrectionStrength.getValue().budget()
            PinyinCorrector.decode(raw, budget)
                .filter { it.compactCode != raw }
                .take(ILLEGAL_VARIANTS)
                .map { Variant(it.code, it.code.split('\''), -it.cost) }
        }
        if (variants.isEmpty()) return null

        // 裁决：权重定胜负，同权重时用生成阶段的启发式分（邻键更近 / 编辑代价更低）
        val ranked = variants
            .mapNotNull { v ->
                val weight = weightOf(lexicon, v.syllables)
                if (beats(weight, originalWeight)) v.copy(weight = weight) else null
            }
            .sortedWith(compareByDescending<Variant> { it.weight }.thenByDescending { it.score })
        val topWeight = ranked.firstOrNull()?.weight ?: 0f
        val hits = ranked
            .filter { it.weight >= topWeight - RELATIVE_MARGIN }
            .take(MAX_ITEMS)

        if (hits.isEmpty()) {
            // 近键没有结论时，才考虑模糊音这种纯召回扩展（默认关，结果只放最后）
            if (clean && prefs.input.pinyinFuzzyEnable.getValue() && realCandidates.isNotEmpty()) {
                val fuzzy = buildFuzzy(raw, existingTexts)
                if (fuzzy.isNotEmpty()) {
                    Log.d(TAG, "fuzzy raw=$raw -> $fuzzy")
                    return Result(fuzzy, Placement.TAIL)
                }
            }
            return null
        }

        val texts = collectTexts(hits, existingTexts)
        if (texts.isEmpty()) return null

        // **输入是否合法，决定谁坐首位。**
        //
        // 切不干净（`zlvlnv`、`lsogong`）意味着 Rime 的整条候选链都建立在错误切分上：
        // 它会把每个字母单独当音节、逐音节各挑一个高频字，拼出「之旅靓女」「来说讴歌欧帖」
        // 这类覆盖全文的整句，还因为「覆盖全部输入」排在最前。这类结果没有参考价值，
        // 不是个别候选有问题，而是**整个列表的前提就不成立**——所以不逐个甄别，直接让
        // 纠错结果接管首位。
        //
        // 切得开（`bihao`、`nihao`）说明输入本身有效，Rime 的候选就是对的，
        // 纠错只作为补充排在首位之后，绝不改动正常排序。
        val placement = if (clean) Placement.AFTER_FIRST else Placement.FRONT
        Log.d(TAG, "${if (clean) "near-key" else "correction"} raw=$raw orig=$originalWeight " +
            "hits=${hits.map { "${it.code}(${it.weight})" }} -> $texts placement=$placement")
        return Result(texts, placement)
    }

    /** 一条待裁决的变体 */
    private data class Variant(
        /** 带音节分隔符的编码，交给 [probe] 用 */
        val code: String,
        val syllables: List<String>,
        /** 生成阶段的启发式分，仅用于同权重时排序 */
        val score: Float,
        val weight: Float = 0f,
    )

    /** 原输入不在词库时看绝对门槛，否则看「明显更好」 */
    private fun beats(weight: Float, originalWeight: Float): Boolean =
        if (originalWeight <= 0f) weight >= MIN_KNOWN_WEIGHT
        else weight >= originalWeight + WEIGHT_MARGIN

    /** 音节序列 → 引擎词库里的最高权重；有音节不在引擎音节表里就当 0 */
    private fun weightOf(lexicon: EngineLexicon, syllables: List<String>): Float {
        if (syllables.isEmpty()) return 0f
        val ids = IntArray(syllables.size) { EngineSyllabary.idOf(syllables[it]) }
        if (ids.any { it < 0 }) return 0f
        return lexicon.maxWeight(ids)
    }

    /** 拼音串 → 最高权重，把所有合法切分都试一遍取最大值（`xian` vs `xi+an`） */
    private fun weightOfRaw(lexicon: EngineLexicon, raw: String): Float {
        var best = 0f
        for (split in PinyinCorrector.allSplits(raw)) {
            val weight = weightOf(lexicon, split)
            if (weight > best) best = weight
            // 已经命中主音节切分（`xian` 这种单音节高权重）就不用再试
            if (best >= STRONG_WEIGHT) break
        }
        return best
    }

    /** 权重到这个量级基本可以确定是个真词，不必再试其它切分 */
    private const val STRONG_WEIGHT = 12.0f

    // ---------------------------------------------------------------- 取词

    /**
     * 只对已经胜出的编码做影子查询，拿回候选文本。
     * 每个变体最多贡献 [TEXTS_PER_VARIANT] 条，总数不超过 [MAX_ITEMS]。
     */
    private fun collectTexts(hits: List<Variant>, existingTexts: Collection<String>): List<String> {
        val seen = HashSet<String>(existingTexts.size + MAX_ITEMS * 2)
        existingTexts.forEach { seen.add(it.lowercase(Locale.ROOT)) }

        val out = ArrayList<String>(MAX_ITEMS)
        for (hit in hits) {
            var taken = 0
            for (text in probe(hit.code)?.texts.orEmpty()) {
                if (out.size >= MAX_ITEMS || taken >= TEXTS_PER_VARIANT) break
                // 回声项（编码本身）、英文、emoji 都不是纠错的目标
                if (cjkCount(text) == 0) continue
                if (!seen.add(text.lowercase(Locale.ROOT))) continue
                out.add(text)
                taken++
            }
            if (out.size >= MAX_ITEMS) break
        }
        return out
    }

    private fun buildFuzzy(raw: String, existingTexts: Collection<String>): List<String> {
        val seen = HashSet<String>(existingTexts.size + FUZZY_ITEMS * 2)
        existingTexts.forEach { seen.add(it.lowercase(Locale.ROOT)) }
        val out = ArrayList<String>(FUZZY_ITEMS)
        for (variant in PinyinCorrector.fuzzyVariants(raw, FUZZY_VARIANTS)) {
            for (text in probe(variant)?.texts.orEmpty()) {
                if (out.size >= FUZZY_ITEMS) break
                if (!isUsableCandidate(text, variant)) continue
                if (!seen.add(text.lowercase(Locale.ROOT))) continue
                out.add(text)
            }
        }
        return out
    }

    /** 一次影子查询的结果 */
    private class ProbeData(val texts: List<String>)

    /**
     * 用纠正后的编码做一次影子查询，拿完候选立刻还原组合串。
     * `replaceRimeKey(caret, len, key)` 的语义是「把组合串的 [caret, caret+len) 换成 key」，
     * 全串替换就是 `(0, 全串长度)`；这条链路 `KeyRecordStack.replacePinyinWithT9Keys` 已经在用。
     * 「清空重放」只作为兜底（成本高：最多 24 次词典查询）。
     * 结果（含查不到）按编码缓存，相邻按键里同一个变体编码反复出现时不必再碰引擎。
     */
    private fun probe(code: String): ProbeData? {
        val cached = probeCache[code]
        if (cached != null) return if (cached === EMPTY) null else cached as ProbeData

        val saved = Rime.compositionText
        if (saved.isEmpty()) return null

        // 两条路都要留：组合串带自动分隔符时（l's'o'gong），整串替换 replaceKey 会换不过去，
        // 只有「清空重放」能落到目标编码上——去掉它纠错会整块失效（实测 2026-10-19 23:55）。
        val queried = replaceComposition(code) || replayComposition(code)
        if (!queried) {
            Log.w(TAG, "probe failed: composition=$code unfetchable, saved=$saved")
            restoreComposition(saved)
            return null
        }

        val texts = try {
            val all = Rime.getCandidates(0, PROBE_CANDIDATES)
            // 只认「拼音注释就是这个编码」的候选：拿回上一次查询的残留菜单时，注释会对不上，
            // 直接丢掉，避免把「稍 / 算」这种无关词当成纠错结果（beijingg 的实际踩坑）
            val matched = all.filter { letters(it.comment) == letters(code) }
            if (matched.isEmpty() && all.isNotEmpty()) {
                Log.d(TAG, "probe $code: comments mismatch ${all.take(2).map { it.comment }}")
            }
            ProbeData(matched.map { it.text })
        } catch (t: Throwable) {
            Log.w(TAG, "probe $code failed: ${t.message}")
            null
        } finally {
            restoreComposition(saved)
        }
        // 只缓存「查成功」的结果：查询本身失败是临时状态，缓存了会让这条编码再也不纠
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

    // ---------------------------------------------------------------- 候选可信度

    /**
     * 候选的拼音是否真的对应词库里的词条。
     *
     * `pinyin.table.bin` 是引擎出**词条候选**用的词典；而 Rime 的 `script_translator` 还会
     * 额外拼一批「整句」候选——它把每个字母单独当音节、逐音节各挑一个高频字，串成
     * 「来说讴歌欧帖」「之旅靓女」这种覆盖全部输入的长串。这类串**不在词库里**，
     * 实测权重恒为 0，而真词（`来说` 13.12、`历史` 13.12、`棕榈树` 8.34）都大于 0。
     *
     * 所以「权重是否为 0」就是拼接产物与真词的天然分界，不需要按字数、音节数或
     * 具体词去猜。
     *
     * 三种情况下返回 true（保守，不降级）：词库未就绪、注释为空（英文/emoji/自定义候选）、
     * 注释里有引擎音节表不认识的音节——判不了就不动。
     */
    fun isVerifiedWord(comment: String): Boolean {
        verifiedCache[comment]?.let { return it }
        val result = checkVerifiedWord(comment)
        verifiedCache[comment] = result
        return result
    }

    private fun checkVerifiedWord(comment: String): Boolean {
        val lexicon = EngineLexicon.shared() ?: return true
        val syllables = syllablesOfComment(comment)
        if (syllables.isEmpty()) return true
        val ids = IntArray(syllables.size) { EngineSyllabary.idOf(syllables[it]) }
        if (ids.any { it < 0 }) return true
        return lexicon.maxWeight(ids) > 0f
    }

    /**
     * 把候选的拼音注释拆成音节。
     *
     * 注释形如 `lai'shuo'ou'ge` 或带声调的 `lái'shuō`：字母（含带调字母）拼进当前音节，
     * 其余字符（撇号、空格）都是分隔符。带 `~` 前缀的是模糊音标记，不参与判断。
     */
    private fun syllablesOfComment(comment: String): List<String> {
        if (comment.isEmpty() || comment.startsWith("~")) return emptyList()
        val out = ArrayList<String>(8)
        val sb = StringBuilder(6)
        comment.forEach { raw ->
            val lower = raw.lowercaseChar()
            val base = if (lower in 'a'..'z') lower else BASE_LETTERS[raw] ?: BASE_LETTERS[lower]
            if (base != null) {
                sb.append(base)
            } else if (sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    // ---------------------------------------------------------------- 过滤

    private fun compact(text: String): String =
        text.replace("'", "").replace(" ", "").lowercase(Locale.ROOT)

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
        text.lowercase(Locale.ROOT).forEach { ch ->
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
