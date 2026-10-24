package com.whale.inputmethod.correction

import kotlin.math.sqrt

/**
 * 拼音音节表、键盘模型与模糊音混淆表。
 *
 * 音节表从 `assets/pinyindb/unicode_to_hanyu_pinyin.txt` 抽取（414 个合法音节），这里内联成常量：
 * 纠错在**每次按键**都会用到它，运行时读资产或建索引都会拖慢首字输入，而 414 个音节只占约 2 KB 常量区。
 *
 * 键盘模型只用于判断「邻键 / 远键」：同一行按 chars 间距，上下排按行距，
 * 与 librime `corrector.cc` 里 1 : 4 的邻键/远键比价保持一致（见设计文档 4.3.2）。
 */
object SyllableTable {

    private const val RAW_SYLLABLES =
        "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu " +
            "ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou " +
            "chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo " +
            "da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo " +
            "e ei en eng er fa fan fang fei fen feng fiao fo fou fu ga gai gan gang gao ge gei gen geng " +
            "gong gou gu gua guai guan guang gui gun guo " +
            "ha hai han hang hao he hei hen heng hm hng hong hou hu hua huai huan huang hui hun huo " +
            "ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun " +
            "ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo " +
            "la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu long lou lu luan lun luo lv lve " +
            "m ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu " +
            "n na nai nan nang nao ne nei nen neng ng ni nian niang niao nie nin ning niu nong nou nu nuan nun nuo nv nve " +
            "o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu " +
            "qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun " +
            "ran rang rao re ren reng ri rong rou ru ruan rui run ruo " +
            "sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai " +
            "shuan shuang shui shun shuo si song sou su suan sui sun suo " +
            "ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo " +
            "wa wai wan wang wei wen weng wo wu xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun " +
            "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun " +
            "za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou " +
            "zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo"

    /** 全部合法音节（414 个，按字典序） */
    val syllables: List<String> = RAW_SYLLABLES.split(' ')

    private val syllableSet: Set<String> = HashSet(syllables)

    /** 弱音节：单独成音节时信息量极低，出现在非首位要额外罚分，否则 `haoo` 会被切成 `hao + o` */
    private val weakSyllables: Set<String> = hashSetOf("a", "o", "e", "m", "n", "ng", "hm", "hng")

    /**
     * 「伪音节」：出现在音节表里，但真实的拼音输入不会这么打。
     *
     * 关键背景：Rime 的 pinyin 方案会把 `zhonguo` 切成 `zhong + u + o`、把 `yiuxi` 切成 `yi + u + xi`
     * 并照常给出候选，所以「Rime 有没有候选 / 拼音注释覆盖不覆盖全」**不能**用来判断输入是否合法。
     * 这里换成一个更严的切分标准：切不开就是打错了。
     */
    private val pseudoSyllables: Set<String> = hashSetOf("u", "m", "n", "ng", "hm", "hng")

    /** 单韵母只有出现在整串开头才算合法（啊/哦/额），`hao + o` 这种尾巴不算 */
    private val singleVowels: Set<String> = hashSetOf("a", "o", "e")

    /**
     * 该音节能否参与「干净切分」。
     * @param atStart 是否是整串的第一个音节
     */
    fun isCleanSyllable(syllable: String, atStart: Boolean): Boolean {
        if (syllable in pseudoSyllables) return false
        if (syllable in singleVowels) return atStart
        return true
    }

    /** 首字母 -> 同一首字母的音节，用于「首字母剪枝」 */
    private val byFirstLetter: Map<Char, List<String>> = syllables.groupBy { it[0] }

    // ---------------------------------------------------------------- 键盘模型

    private val keyPositions: Map<Char, FloatArray> = buildMap {
        val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val offsets = floatArrayOf(0f, 0.25f, 0.75f)
        rows.forEachIndexed { row, keys ->
            keys.forEachIndexed { col, ch ->
                put(ch, floatArrayOf(row.toFloat(), col + offsets[row]))
            }
        }
    }

    fun keyDistance(a: Char, b: Char): Float {
        val pa = keyPositions[a] ?: return 9f
        val pb = keyPositions[b] ?: return 9f
        val dr = pa[0] - pb[0]
        val dc = pa[1] - pb[1]
        return sqrt(dr * dr + dc * dc)
    }

    /** 同一行左右相邻、或上下排正对，视为邻键（阈值 1.15 与原型一致） */
    fun isNearKey(a: Char, b: Char): Boolean = keyDistance(a, b) <= 1.15f

    /**
     * 首字母可能被打成的其他字母：自身 + 邻键 + 口音混淆。
     * 首字母若被打成远键，单这一步就超过预算，所以按首字母剪枝是无损的。
     */
    fun firstLetterCandidates(head: Char): Set<Char> {
        val result = HashSet<Char>(12)
        result.add(head)
        keyPositions.keys.forEach { if (isNearKey(head, it)) result.add(it) }
        result.addAll(INITIAL_CONFUSION[head].orEmpty())
        return result
    }

    private val INITIAL_CONFUSION: Map<Char, Set<Char>> = mapOf(
        'n' to setOf('l', 'n'),
        'l' to setOf('n', 'l'),
        'f' to setOf('h', 'f'),
        'h' to setOf('f', 'h'),
        'r' to setOf('l', 'r'),
    )

    // ---------------------------------------------------------------- 模糊音

    /** 声母模糊：用于代价折扣 */
    private val initialFuzzyPairs: List<Pair<String, String>> = listOf(
        "zh" to "z",
        "ch" to "c",
        "sh" to "s",
        "n" to "l",
        "f" to "h",
        "r" to "l",
    )

    /** 韵母模糊：用于代价折扣 */
    private val finalFuzzyPairs: List<Pair<String, String>> = listOf(
        "ng" to "n",
        "ing" to "in",
        "eng" to "en",
        "ang" to "an",
    )

    /** 全部模糊对（先声母、后韵母），代价折扣与模糊音查询扩展共用 */
    val fuzzyPairs: List<Pair<String, String>> = initialFuzzyPairs + finalFuzzyPairs

    /** 声母互换：`zh ↔ z`、`ch ↔ c`、`sh ↔ s`、`n ↔ l`、`f ↔ h`、`r ↔ l` */
    val initialFuzzy: List<Pair<String, String>> = initialFuzzyPairs

    /** 韵母互换：`ang ↔ an`、`eng ↔ en`、`ing ↔ in`、`ng ↔ n` */
    val finalFuzzy: List<Pair<String, String>> = finalFuzzyPairs

    /** 每个键「手指可及」的邻居：左右相邻、上下相邻，以及错位排版带来的斜向（阈值 1.15） */
    private val neighborMap: Map<Char, List<Char>> = run {
        val chars = keyPositions.keys.toList()
        chars.associateWith { key ->
            chars.filter { it != key && isNearKey(key, it) }
        }
    }

    fun neighbors(ch: Char): List<Char> = neighborMap[ch].orEmpty()

    fun isSyllable(value: String): Boolean = syllableSet.contains(value)

    fun isWeak(value: String): Boolean = weakSyllables.contains(value)

    fun syllablesStartingWith(head: Char): List<String> = byFirstLetter[head].orEmpty()

    /** 是否还有更长的音节以 [prefix] 开头 —— 说明用户可能只是还没打完 */
    fun isPrefixOfLongerSyllable(prefix: String): Boolean =
        prefix.isNotEmpty() &&
            byFirstLetter[prefix[0]]?.any { it.length > prefix.length && it.startsWith(prefix) } == true

    /**
     * 是否是**半个音节**：自己还不是合法音节，但还有合法音节以它打头（`h` -> ha/hai/…，`zh` -> zha/…）。
     *
     * 这是「用户正在往下打」的语言学证据：一个合法音节之后的半个音节，只能是下一个音节的声母，
     * 而不会是一次按键失误。注意不能直接用 [isPrefixOfLongerSyllable]：它把 `ni`、`ying` 这种
     * 「本身合法、但还能延长」的音节也算作前缀，那是**打完的音节**，不是半个。
     */
    fun isIncompleteSyllable(prefix: String): Boolean =
        !syllableSet.contains(prefix) && isPrefixOfLongerSyllable(prefix)
}
