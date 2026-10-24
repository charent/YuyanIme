package com.whale.inputmethod.correction

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 音节级拼音纠错生成器：**纯 Kotlin、无 Android 依赖**，可直接在 JVM 上跑回归用例。
 *
 * 与设计文档 `docs/pinyin-correction-design.md` 第 4.3 节一一对应：
 * - 候选落在 414 个合法音节上，用带权 Damerau-Levenshtein 算「音节内编辑代价」；
 * - 在字符串上做 DP 切分，每层 beam 剪枝，只解码末尾 8 个字母（前缀已确定的部分不动）；
 * - 输出 Top-N **编码**而不是唯一答案，最终排序交给 Rime 词库（见 [CorrectionSession]）。
 *
 * 结构性保证：合法输入存在代价 0 的路径，任何纠错路径都不会更便宜，
 * 再加上上层「只在不可解析时触发」，所以合法输入不会被自动改写。
 */
object PinyinCorrector {

    /** 只解码末尾这么多字母：改动几乎都发生在字尾，前缀不动可以少算一半 */
    const val MAX_WINDOW = 8

    /**
     * 一次解码最多产出多少条假设。
     *
     * 解码本身只是拼字符串（微秒级），真正的成本在调用方对每条假设做一次词库查表。
     * 这里给得宽一些，是为了让调用方按自己的硬约束（末尾半个音节、别丢键）筛完以后
     * 手里还有足够的候选；调用方自己只挑前 20 条去查库。
     */
    const val MAX_HYPOTHESES = 32

    /** 拼音音节最长 6 个字母（`zhuang` / `chuang`），补齐时不必越过它 */
    private const val MAX_SYLLABLE_LENGTH = 6

    /** 同一个末音节最多保留几条假设（它们只差前面的音节，词的身份几乎一样） */
    private const val PER_ENDING = 6

    private const val MAX_FINALS = 48

    /**
     * 每层保留多少条路径（中间层）。
     *
     * 中间层只是「前缀怎么切」，变体有限，12 条足够；**最后一层不一样**：它要把
     * `lan'?a'ji` / `lan'?a'ju` / `lan'?a'jie` 这类同形变体全展开，按代价截断必然把
     * 真正的 `lan'da'jie`（2.9）挤掉，所以末层改用 [pruneEndings] 按末音节去重。
     */
    private const val BEAM = 12

    /** 默认总预算，对应「标准」档 */
    const val DEFAULT_BUDGET = 2.2f

    // ---------------------------------------------------------------- 代价模型（4.3.2）

    private const val COST_SUB_NEAR = 0.9f      // 邻键替换
    private const val COST_SUB_FAR = 1.6f       // 远键替换
    private const val COST_TRANSPOSE = 0.8f     // 相邻换位：shenem / hoa 的主要来源
    private const val COST_INSERT = 1.0f        // 多打
    private const val COST_DELETE = 1.0f        // 少打
    private const val COST_DOUBLE = 0.4f        // 双写折叠：haoo / beijingg / youxii
    private const val COST_WEAK = 1.2f          // 非首位的弱音节
    private const val COST_CLASS = 0.8f         // 元音↔辅音替换
    private const val COST_PHONETIC_DISCOUNT = 0.3f

    /** 元音集合（含 v，Rime 里 ü 打成 v） */
    private const val VOWELS = "aeiouv"

    /** 一次纠错假设：纠正后的编码（音节用 `'` 分隔）、总代价、编辑次数 */
    data class Hypothesis(val code: String, val cost: Float, val edits: Int) {
        /** 去掉分隔符的紧凑编码，用于和原始输入比对 */
        val compactCode: String get() = code.replace("'", "")

        /** 按音节拆开，最后一个音节就是被补齐/改写的那个 */
        val syllables: List<String> get() = code.split('\'')
    }

    private data class Path(val cost: Float, val edits: Int, val syllables: List<String>)

    // ---------------------------------------------------------------- 缓存

    /** spellCost 的热点集中在少数几个子串上，缓存能省掉一半以上的 DP */
    private val spellCostCache = LruCache<String, SpellResult>(4096)
    private val nearSyllableCache = LruCache<String, List<NearSyllable>>(512)

    private data class SpellResult(val cost: Float, val edits: Int)

    private data class NearSyllable(val syllable: String, val cost: Float, val edits: Int)

    // ---------------------------------------------------------------- 对外入口

    fun normalize(code: String): String = code.replace("'", "").replace(" ", "").lowercase()

    /**
     * 生成纠错假设，按代价升序。返回的编码本身**还没有经过词库验证**。
     *
     * @param budget 总代价预算（保守 1.6 / 标准 2.2 / 激进 3.0）
     */
    fun decode(code: String, budget: Float = DEFAULT_BUDGET, topK: Int = MAX_HYPOTHESES): List<Hypothesis> {
        val raw = normalize(code)
        if (raw.isEmpty() || raw.any { it !in 'a'..'z' }) return emptyList()

        val window = if (raw.length <= MAX_WINDOW) raw else raw.substring(raw.length - MAX_WINDOW)
        val prefix = raw.substring(0, raw.length - window.length)
        val n = window.length

        val states = Array(n + 1) { mutableListOf<Path>() }
        states[0].add(Path(0f, 0, emptyList()))

        for (i in 0 until n) {
            if (states[i].isEmpty()) continue
            // states[i] 在上一轮已经剪过枝，这里可以直接遍历
            for (path in states[i]) {
                val end = minOf(i + 7, n)
                for (j in i + 1..end) {
                    val sub = window.substring(i, j)
                    if (SyllableTable.isSyllable(sub)) {
                        append(states[j], path, sub, 0f, 0, i, budget)
                    }
                    // 剩余预算按 0.5 取整再查：原始剩余预算随每条路径连续变化，缓存键会被打散，
                    // 同一子串要重算几十遍 spellCost；取整后既保留剪枝，又能命中缓存。
                    nearSyllables(sub, quantize(budget - path.cost)).forEach { near ->
                        if (near.syllable != sub) {
                            append(states[j], path, near.syllable, near.cost, near.edits, i, budget)
                        }
                    }
                    // 末段可能只是「还没打完」：`x` -> `xiang`（影响）、`t` -> `tian`（今天）。
                    // nearSyllables 为了剪枝把编辑次数限制在 2 以内，长补齐会被误杀，
                    // 所以这里对**纯延长**（音节以已打字母开头）不再限制编辑次数，
                    // 代价仍按每补一个字母 1.0 计，够不够由预算决定。
                    if (j == n && sub.length < MAX_SYLLABLE_LENGTH) {
                        SyllableTable.syllablesStartingWith(sub[0]).forEach { syl ->
                            if (syl.length > sub.length && syl.startsWith(sub)) {
                                val extra = syl.length - sub.length
                                append(states[j], path, syl, COST_INSERT * extra, extra, i, budget)
                            }
                        }
                    }
                }
            }
            states[i + 1] = if (i + 1 == n) pruneEndings(states[i + 1]) else prune(states[i + 1])
        }

        val finals = states[n]
        if (finals.isEmpty()) return emptyList()

        val out = ArrayList<Hypothesis>(topK)
        val seen = HashSet<String>(topK * 2)
        for (path in finals) {
            val corrected = prefix + path.syllables.joinToString("'")
            if (!seen.add(corrected)) continue
            out.add(Hypothesis(corrected, round3(path.cost), path.edits))
            if (out.size >= topK) break
        }
        return out
    }

    /**
     * 整串能否切成「干净」的音节链——纠错的第一道闸（合法输入一律不动）。
     *
     * 不能用「Rime 有没有候选」来判定：Rime 会把 `zhonguo` 切成 `zhong+u+o`、把 `yiuxi` 切成
     * `yi+u+xi`，照样给出候选（中欧 / 以），所以那个信号恒为「有候选」。
     * 这里用严格音节表做一次可行性 DP：切开说明是合法输入。
     *
     * 切不开只是「可疑」，不能直接判成打错——还得再过 [hasIncompleteTail]：
     * `ni'h` 同样切不开，但它是用户打到一半，不是错键。
     */
    fun hasCleanParse(code: String): Boolean {
        val raw = normalize(code)
        if (raw.isEmpty() || raw.any { it !in 'a'..'z' }) return false
        return cleanPrefixEnds(raw)[raw.length]
    }

    /**
     * 输入是否**还没打完**——末尾只剩半个音节。返回 true 的输入一律不该纠错。
     *
     * ```
     * ni'h   ->  ni | h      h 是 hai/hui… 的声母       用户正在打「你会」
     * ying'x ->  ying | x    x 是 xiang/xiao… 的声母    用户正在打「影响」
     * zhon   ->  (空) | zhon 整串是 zhong/zhou 的前缀
     * ```
     *
     * 这三种输入用 [hasCleanParse] 判断都是「切不开」，但它们是**打对了一半**，不是打错：
     * 把整串当成错误编码去解码，就会拿已经打对的 `ni` / `ying` 去查词库，
     * 那些高权重的单字（呢、应、影）于是被判成「比原输入更好」而顶到首位。
     * 所以判定必须是「整串 + 未完成的尾巴」，而不是对整串求一次可行性。
     *
     * 例外：末尾连按两下同一个键（`beijingg`）不算半个音节——没有音节以两个相同字母开头，
     * 那是重复按键，仍交给纠错处理。
     */
    fun hasIncompleteTail(code: String): Boolean = incompleteTail(code) != null

    /**
     * 末尾那半个音节（`ni'h` -> `h`、`lan'fa'j` -> `j`、`zhon` -> `zhon`）；末尾完整时返回 null。
     *
     * 它是**下一个音节的声母**，所以后续裁决可以拿它当硬约束：纠错假设的最后一个音节
     * 必须包含这几个字母，不能把它删掉、也不能换成别的声母。
     *
     * 例外：末尾连按两下同一个键（`beijingg`、`haoo`）不算半个音节——没有音节以两个
     * 相同字母开头，那是重复按键，仍交给纠错处理。
     */
    fun incompleteTail(code: String): String? {
        val raw = normalize(code)
        if (raw.length < 2 || raw.any { it !in 'a'..'z' }) return null
        if (raw[raw.length - 1] == raw[raw.length - 2]) return null
        val ends = cleanPrefixEnds(raw)
        // 从最长的干净前缀往回找：它后面剩下的就是「半个音节」
        for (k in raw.length - 1 downTo 0) {
            if (!ends[k]) continue
            val tail = raw.substring(k)
            return if (SyllableTable.isIncompleteSyllable(tail)) tail else null
        }
        return null
    }

    /** 可达性 DP：`result[i]` 表示前 i 个字母能否切成干净的音节链（`result[0]` 恒为 true） */
    private fun cleanPrefixEnds(raw: String): BooleanArray {
        val reachable = BooleanArray(raw.length + 1)
        reachable[0] = true
        for (i in raw.indices) {
            if (!reachable[i]) continue
            for (j in i + 1..minOf(i + 6, raw.length)) {
                val sub = raw.substring(i, j)
                if (!SyllableTable.isSyllable(sub)) continue
                if (!SyllableTable.isCleanSyllable(sub, atStart = i == 0)) continue
                reachable[j] = true
            }
        }
        return reachable
    }

    /**
     * 近键替换变体：`纠正后的编码` + 一个便宜的启发式分数（真正的排序交给词库，见 CorrectionSession）。
     */
    data class NearKeyVariant(val code: String, val score: Float)

    /**
     * 「前缀 + 末半个音节」的直接枚举。
     *
     * 这类输入的结构是确定的：前面已经是干净音节链，只有最后一个音节差几个字母。于是要回答的
     * 只有两件事：
     * 1. 前缀本身对不对（`lan'fa'j` 里的 `fa` 应该是 `da`）；
     * 2. 那半个音节补成什么（`j` -> `jie`、`h` -> `hao`）。
     *
     * 让通用解码器去覆盖这件事既慢又不准：它要在几十条同代价路径（`lan'?a'ji` / `lan'?a'ju` /
     * `lan'?a'jie`…）里按代价截断，`lan'da'jie`（烂大街，4.61）常常恰好被挤出去；把名额放宽到
     * 能容纳它，解码时间又从 2 ms 涨到 9 ms。直接按这两个维度枚举，候选只有
     * 「补齐音节数 + 邻键前缀数 × 补齐音节数」，谁胜出完全交给词库裁决。
     *
     * @param prefixFixes 前缀最多试几种单键邻键替换（按邻键分数降序，只看第一个改动的音节）
     * @param endingsPerFix 每种前缀最多配几个补齐音节（按补齐代价升序）
     * @param maxCompletion 允许补几个字母的代价上限（默认 2 个字母）
     */
    fun completionVariants(
        code: String,
        prefixFixes: Int = 6,
        endingsPerFix: Int = 6,
        typedEndings: Int = 12,
        maxCompletion: Float = 2.0f,
    ): List<Hypothesis> {
        val raw = normalize(code)
        val tail = incompleteTail(raw) ?: return emptyList()
        val prefixLetters = raw.substring(0, raw.length - tail.length)
        val endings = endingsFor(tail, maxCompletion)
        if (endings.isEmpty()) return emptyList()

        val out = ArrayList<Hypothesis>(typedEndings + prefixFixes * endingsPerFix)
        // 1) 前缀原样：这一支要尽量给全，它决定「用户是不是还没打完」
        val typedCode = parseSyllables(prefixLetters).orEmpty().joinToString("'")
        endings.take(typedEndings).forEach { (syllable, cost) ->
            val completion = if (typedCode.isEmpty()) syllable else "$typedCode'$syllable"
            out.add(Hypothesis(completion, cost, letters2Edits(cost)))
        }
        // 2) 前缀按邻键改一位：`lan'fa` -> `lan'da`
        var used = 0
        for (variant in nearKeyVariants(typedCode, prefixFixes * 4)) {
            if (used >= prefixFixes) break
            val syllables = variant.code.split('\'')
            if (syllables.any { !SyllableTable.isSyllable(it) }) continue
            used++
            endings.take(endingsPerFix).forEach { (syllable, cost) ->
                out.add(Hypothesis(variant.code + "'" + syllable, COST_SUB_NEAR + cost, letters2Edits(cost) + 1))
            }
        }
        return out
    }

    /** 以半个音节 [tail] 打头、补齐代价不超过 [maxCost] 的完整音节，按代价升序 */
    private fun endingsFor(tail: String, maxCost: Float): List<Pair<String, Float>> =
        SyllableTable.syllablesStartingWith(tail[0])
            .asSequence()
            .filter { it.length > tail.length && it.startsWith(tail) }
            .map { it to COST_INSERT * (it.length - tail.length) }
            .filter { it.second <= maxCost }
            .sortedBy { it.second }
            .toList()

    private fun letters2Edits(cost: Float): Int = (cost / COST_INSERT).toInt()

    /**
     * **穷尽**生成「单个相邻键按错」的变体，供合法输入使用。
     *
     * 做法不是"猜哪几个位置像"，而是把每个字母都换成它在键盘上相邻的键（左右、上下、错位斜向），
     * 再用**原串的干净切分**做基准：只换掉一个音节里的一个字符、其余音节不动，
     * 于是"这个变体是否还是合法拼音"退化成一次 [SyllableTable.isSyllable] 的哈希查表 —— O(1)。
     *
     * 以 `bihao`（bi + hao）为例会生成 `nihao`（b→n）、`buhao`（i→u）、`bigao`（h→g）、
     * `biyao`（h→y）、`bibao`（h→b）、`bihai`（o→i）…… 再由上层用词库验证并排序。
     *
     * 复杂度：O(字符数 × 相邻键数)，典型 8 个字母 × 4 个邻居 = 32 次查表，微秒级。
     *
     * @param max 最多返回多少个变体（按启发式分数降序）
     */
    fun nearKeyVariants(code: String, max: Int = 12): List<NearKeyVariant> {
        val syllables = parseSyllables(normalize(code)) ?: return emptyList()
        if (syllables.isEmpty()) return emptyList()

        // 这里**不能**用「末尾音节还是更长音节的前缀」来拦：`fan` 是 `fang` 的前缀、`dan` 是 `dang` 的前缀、
        // `bi` 是 `bian/bin` 的前缀…… 一拦就把 -an / -i / -u 这些开音节整片漏掉
        // （`mingfan` -> `mingdan`、`mingdan` -> `mingfan` 两个方向全都出不来）。
        // 「用户可能还没打完」的判断交给上层：它知道当前候选是什么，可以在这种情况下要求更高的词频
        // （CorrectionSession.buildNearKey 的 ambiguousTail），而不是在生成阶段一刀切。

        val variants = LinkedHashMap<String, Float>(16)
        syllables.forEachIndexed { syllableIndex, syllable ->
            for (indexInSyllable in syllable.indices) {
                val typed = syllable[indexInSyllable]
                for (neighbour in SyllableTable.neighbors(typed)) {
                    val replaced = syllable.substring(0, indexInSyllable) +
                        neighbour + syllable.substring(indexInSyllable + 1)
                    if (replaced == syllable || !SyllableTable.isSyllable(replaced)) continue
                    val variantCode = buildString(syllables.size * 3) {
                        syllables.forEachIndexed { i, value ->
                            if (i > 0) append('\'')
                            append(if (i == syllableIndex) replaced else value)
                        }
                    }
                    val score = nearKeyScore(typed, neighbour, indexInSyllable)
                    val previous = variants[variantCode]
                    if (previous == null || score > previous) variants[variantCode] = score
                }
            }
        }
        if (variants.isEmpty()) return emptyList()
        return variants.entries
            .sortedByDescending { it.value }
            .take(max)
            .map { NearKeyVariant(it.key, it.value) }
    }

    /**
     * 便宜的启发式打分，只用来决定「先探测哪几个」（探测次数有预算）。
     * 真正决定展示顺序的是词频（见 CorrectionSession.buildNearKey）。
     *
     * 注意这里**不**按「靠后音节」加权：误按可能发生在任何位置（`bihao` 的 b→n 就在第一个音节），
     * 按位置加权会让某些输入永远轮不到探测预算。
     */
    private fun nearKeyScore(typed: Char, neighbour: Char, indexInSyllable: Int): Float {
        var score = 0f
        // 音节首字母（声母）按错最常见
        if (indexInSyllable == 0) score += 2f
        // 同一行左右相邻比上下、斜向更常见
        score += (1.2f - SyllableTable.keyDistance(typed, neighbour)).coerceAtLeast(0f)
        // 元音换元音 / 辅音换辅音更可信
        if ((typed in VOWELS) == (neighbour in VOWELS)) score += 0.15f
        return score
    }

    /**
     * 把编码按「代价最小、音节数最多」切成音节；切不开返回 null。
     * 模糊音查询扩展需要知道音节边界，才能只在声母/韵母上做替换。
     */
    fun parseSyllables(code: String): List<String>? {
        val raw = normalize(code)
        if (raw.isEmpty() || raw.any { it !in 'a'..'z' }) return null
        val n = raw.length
        val best = arrayOfNulls<Path>(n + 1)
        best[0] = Path(0f, 0, emptyList())
        for (i in 0 until n) {
            val current = best[i] ?: continue
            for (j in i + 1..minOf(i + 6, n)) {
                val sub = raw.substring(i, j)
                if (!SyllableTable.isSyllable(sub)) continue
                var cost = current.cost
                if (i > 0 && SyllableTable.isWeak(sub)) cost += COST_WEAK
                val candidate = Path(cost, 0, current.syllables + sub)
                val old = best[j]
                val better = old == null ||
                    candidate.cost < old.cost - 1e-4f ||
                    (abs(candidate.cost - old.cost) <= 1e-4f && candidate.syllables.size > old.syllables.size)
                if (better) best[j] = candidate
            }
        }
        return best[n]?.syllables
    }

    /**
     * 枚举编码的**全部合法切分**（按音节数从少到多，最多 [limit] 种）。
     *
     * [parseSyllables] 在代价相同时偏向「音节数最多」，这对生成变体是对的，但用来查词库权重会出事：
     * `xian` 会被切成 `xi + an`（西安），而用户想打的是 `xian`（先/现/线）。同一个字母串在引擎词库里
     * 可能同时存在多种切分的词条，所以查权重时必须把几种切分都试一遍、取最大值。
     */
    fun allSplits(code: String, limit: Int = 4): List<List<String>> {
        val raw = normalize(code)
        if (raw.isEmpty() || raw.any { it !in 'a'..'z' }) return emptyList()
        val n = raw.length
        val paths = Array(n + 1) { ArrayList<List<String>>() }
        paths[0].add(emptyList())
        for (i in 0 until n) {
            if (paths[i].isEmpty()) continue
            for (j in i + 1..minOf(i + 6, n)) {
                val sub = raw.substring(i, j)
                if (!SyllableTable.isSyllable(sub)) continue
                for (path in paths[i]) {
                    if (paths[j].size >= limit * 4) break
                    paths[j].add(path + sub)
                }
            }
            // 中间状态也要剪枝，否则长串的切分数量是斐波那契级
            if (paths[i].size > limit * 2) {
                paths[i] = ArrayList(paths[i].sortedBy { it.size }.take(limit * 2))
            }
        }
        return paths[n]
            .distinct()
            .sortedBy { it.size }
            .take(limit)
    }

    /**
     * 模糊音查询扩展：只在**合法切分**的声母/韵母上做一对互换，最多 [max] 个变体。
     * 与纠错分开：这里的输入本身合法，目的只是扩大召回，结果一律排在候选尾部。
     */
    fun fuzzyVariants(code: String, max: Int = 2): List<String> {
        val raw = normalize(code)
        val syllables = parseSyllables(raw) ?: return emptyList()
        if (syllables.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (index in syllables.indices) {
            val syllable = syllables[index]
            for ((a, b) in SyllableTable.initialFuzzy) {
                val swapped = swapInitial(syllable, a, b) ?: continue
                out.add(replaceAt(syllables, index, swapped))
                if (out.size >= max) return out.toList()
            }
            for ((a, b) in SyllableTable.finalFuzzy) {
                val swapped = swapFinal(syllable, a, b) ?: continue
                out.add(replaceAt(syllables, index, swapped))
                if (out.size >= max) return out.toList()
            }
        }
        return out.toList()
    }

    // ---------------------------------------------------------------- 音节内编辑距离

    private fun append(
        bucket: MutableList<Path>,
        path: Path,
        syllable: String,
        cost: Float,
        edits: Int,
        position: Int,
        budget: Float,
    ) {
        var totalCost = path.cost + cost
        var totalEdits = path.edits + edits
        // 弱音节出现在非首位：必须比「删除」更贵，否则 `haoo` 会被解释成 `hao + o`
        if (position > 0 && SyllableTable.isWeak(syllable)) {
            totalCost += COST_WEAK
            totalEdits += 1
        }
        if (totalCost <= budget) {
            bucket.add(Path(totalCost, totalEdits, path.syllables + syllable))
        }
    }

    /**
     * 只用「首字母可能被误打」的音节子集做比较，避免每次扫 414 个音节。
     * 首字母若被打成远键，单这一步就 1.6，加上后续编辑必然超预算，所以剪枝无损。
     */
    private fun nearSyllables(typed: String, budget: Float): List<NearSyllable> {
        if (typed.isEmpty() || budget < 0f) return emptyList()
        val cacheKey = typed + "|" + (budget * 100).roundToInt()
        nearSyllableCache[cacheKey]?.let { return it }

        val allowedHeads = HashSet<Char>(12)
        // 第二个字符也可能因换位变成音节首字母
        typed.take(2).forEach { allowedHeads.addAll(SyllableTable.firstLetterCandidates(it)) }

        val out = ArrayList<NearSyllable>(16)
        allowedHeads.forEach { head ->
            SyllableTable.syllablesStartingWith(head).forEach { syllable ->
                if (abs(syllable.length - typed.length) > 2) return@forEach
                val (cost, edits) = spellCost(typed, syllable)
                if (cost <= budget && edits <= 2) {
                    out.add(NearSyllable(syllable, cost, edits))
                }
            }
        }
        out.sortWith(compareBy({ it.cost }, { it.syllable.length }))
        nearSyllableCache[cacheKey] = out
        return out
    }

    /** 带权 Damerau-Levenshtein，返回 (代价, 编辑次数) */
    private fun spellCost(typed: String, target: String): SpellResult {
        val cacheKey = "$typed>$target"
        spellCostCache[cacheKey]?.let { return it }

        val m = typed.length
        val n = target.length
        val inf = Float.MAX_VALUE / 4
        val cost = Array(m + 1) { FloatArray(n + 1) { inf } }
        val edits = Array(m + 1) { IntArray(n + 1) { 99 } }
        cost[0][0] = 0f
        edits[0][0] = 0
        for (i in 1..m) {
            cost[i][0] = i * COST_INSERT
            edits[i][0] = i
        }
        for (j in 1..n) {
            cost[0][j] = j * COST_DELETE
            edits[0][j] = j
        }
        for (i in 1..m) {
            for (j in 1..n) {
                // typed 多出一个字符：若与前一个字相同，按「双写折叠」算，代价极低
                val extra = if (i > 1 && typed[i - 1] == typed[i - 2]) COST_DOUBLE else COST_INSERT
                var bestCost = cost[i - 1][j] + extra
                var bestEdits = edits[i - 1][j] + 1
                if (cost[i][j - 1] + COST_DELETE < bestCost) {
                    bestCost = cost[i][j - 1] + COST_DELETE
                    bestEdits = edits[i][j - 1] + 1
                }
                val same = typed[i - 1] == target[j - 1]
                val subCost = cost[i - 1][j - 1] + subCost(typed[i - 1], target[j - 1])
                if (subCost < bestCost) {
                    bestCost = subCost
                    bestEdits = edits[i - 1][j - 1] + if (same) 0 else 1
                }
                if (i > 1 && j > 1 && typed[i - 2] == target[j - 1] && typed[i - 1] == target[j - 2]) {
                    val transCost = cost[i - 2][j - 2] + COST_TRANSPOSE
                    if (transCost < bestCost) {
                        bestCost = transCost
                        bestEdits = edits[i - 2][j - 2] + 1
                    }
                }
                cost[i][j] = bestCost
                edits[i][j] = bestEdits
            }
        }

        var resultCost = cost[m][n]
        var resultEdits = edits[m][n]
        if (resultEdits > 0 && isFuzzyPair(typed, target)) {
            resultCost = maxOf(0.4f, resultCost - COST_PHONETIC_DISCOUNT)
        }
        return SpellResult(resultCost, resultEdits).also { spellCostCache[cacheKey] = it }
    }

    private fun subCost(a: Char, b: Char): Float {
        if (a == b) return 0f
        var cost = if (SyllableTable.isNearKey(a, b)) COST_SUB_NEAR else COST_SUB_FAR
        val vowelA = a in VOWELS
        val vowelB = b in VOWELS
        if (vowelA != vowelB) cost += COST_CLASS
        return cost
    }

    /** 两个串之间是否只差一个模糊音对（口音类混淆，应该比随机错键便宜） */
    private fun isFuzzyPair(a: String, b: String): Boolean {
        SyllableTable.fuzzyPairs.forEach { (x, y) ->
            if (replaceFirst(a, x, y) == b || replaceFirst(b, x, y) == a) return true
        }
        return false
    }

    private fun replaceFirst(source: String, from: String, to: String): String {
        val index = source.indexOf(from)
        if (index < 0) return source
        return source.substring(0, index) + to + source.substring(index + from.length)
    }

    // ---------------------------------------------------------------- 切分辅助

    private fun prune(bucket: MutableList<Path>, limit: Int = BEAM): MutableList<Path> {
        if (bucket.isEmpty()) return bucket
        bucket.sortWith(compareBy({ round3(it.cost) }, { -it.syllables.size }))
        val out = ArrayList<Path>(limit)
        val seen = HashSet<String>(bucket.size * 2)
        for (path in bucket) {
            val key = "${round3(path.cost)}:${path.syllables.joinToString(",")}"
            if (!seen.add(key)) continue
            out.add(path)
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * 末层剪枝：按**末音节**去重，每个结尾最多留 [PER_ENDING] 条，总数上限 [MAX_FINALS]。
     *
     * 最后一层是唯一会「炸」的一层：输入末尾那几个字母可以补成几十个不同音节
     * （`j` -> ji/jia/jian/…/jie/…），按代价截断时它们互相挤占名额，而真正要的那条
     * （`lan'da'jie`，2.9）往往不是最便宜的。可纠错最后是由**词库权重**裁决的，
     * 词的身份几乎只由末音节决定——`lan'?a'jie` 是「烂大街」，`lan'?a'ji` 什么都不是。
     * 所以末层只保证「每个结尾都有代表」，谁胜出交给权重，不能让同形变体吃光名额。
     */
    private fun pruneEndings(bucket: MutableList<Path>, limit: Int = MAX_FINALS): MutableList<Path> {
        if (bucket.isEmpty()) return bucket
        bucket.sortWith(compareBy({ round3(it.cost) }, { -it.syllables.size }))
        val perEnding = HashMap<String, Int>(16)
        val out = ArrayList<Path>(limit)
        val seen = HashSet<String>(bucket.size * 2)
        for (path in bucket) {
            val key = path.syllables.joinToString(",")
            if (!seen.add(key)) continue
            val ending = path.syllables.last()
            val used = perEnding[ending] ?: 0
            if (used >= PER_ENDING) continue
            perEnding[ending] = used + 1
            out.add(path)
            if (out.size >= limit) break
        }
        return out
    }

    private fun swapInitial(syllable: String, a: String, b: String): String? {
        // 两个方向都要试：sang -> shang 与 shang -> sang 都是真实口音
        convertInitial(syllable, a, b)?.let { return it }
        return convertInitial(syllable, b, a)
    }

    private fun convertInitial(syllable: String, from: String, to: String): String? {
        if (from == to || !syllable.startsWith(from)) return null
        // 本来就是长形式（zh/ch/sh）的不用再补一遍
        if (to.length > from.length && syllable.startsWith(to)) return null
        val swapped = to + syllable.substring(from.length)
        if (swapped == syllable) return null
        return if (SyllableTable.isSyllable(swapped)) swapped else null
    }

    private fun swapFinal(syllable: String, a: String, b: String): String? {
        if (syllable.endsWith(a) && a.length > b.length) {
            val swapped = syllable.dropLast(a.length) + b
            return if (SyllableTable.isSyllable(swapped)) swapped else null
        }
        if (syllable.endsWith(b) && !syllable.endsWith(a)) {
            val swapped = syllable.dropLast(b.length) + a
            return if (SyllableTable.isSyllable(swapped)) swapped else null
        }
        return null
    }

    private fun replaceAt(syllables: List<String>, index: Int, value: String): String =
        buildString {
            syllables.forEachIndexed { i, s -> append(if (i == index) value else s) }
        }

    private fun round3(value: Float): Float = (value * 1000f).roundToInt() / 1000f

    /** 剩余预算取整到 0.5，让 [nearSyllables] 的缓存键收敛 */
    private fun quantize(budget: Float): Float = ceil(budget * 2f) / 2f

    /** 极简 LRU：纠错的输入是「用户正在打的字」，命中率集中在最近几次按键 */
    private class LruCache<K, V>(private val capacity: Int) : LinkedHashMap<K, V>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > capacity
    }
}
