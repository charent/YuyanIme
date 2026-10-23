package com.yuyan.inputmethod

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.manager.UserDictManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.service.DictDecoder
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.core.Rime
import com.yuyan.inputmethod.correction.CorrectionSession
import com.yuyan.inputmethod.correction.PinyinCorrector
import com.yuyan.inputmethod.data.InputKey
import com.yuyan.inputmethod.data.KeyRecordStack
import com.yuyan.inputmethod.util.DoublePinYinUtils
import com.yuyan.inputmethod.util.LX17PinYinUtils
import com.yuyan.inputmethod.util.QwertyPinYinUtils
import com.yuyan.inputmethod.util.T9PinYinUtils
import java.util.Locale

object RimeEngine {
    private val keyRecordStack = KeyRecordStack()
    private var pinyins: Array<String> = emptyArray() // 候选词界面的候选拼音列表
    var showCandidates: List<CandidateListItem> = emptyList() // 所有待展示的候选词

    /**
     * 与 [showCandidates] 一一对应：每一项在 Rime 原始候选数组里的下标，-1 表示自定义候选（不经过 Rime）。
     * 剔除"编码本身"候选后，展示下标与 Rime 下标不再相等，选词必须查这张表。
     * 这里的下标是**全局下标**（`candidate_list_*` 的顺序），与 `select_candidate` 的入参一致。
     */
    private var rimeIndexes: IntArray = IntArray(0)

    /**
     * 每批候选的条数。
     *
     * 与 schema 的 `menu/page_size`（5）解耦：候选栏靠 [Rime.getCandidates] 按需往后取。
     * 这里的值直接等于「每敲一个键，引擎要翻译 + 过滤多少个候选」——取 30 够铺满一屏候选面板，
     * 又比原先一次算满 100 条少 70% 的工作量；再往后翻只是继续取下一段，不再重算。
     */
    private const val CANDIDATE_PAGE_SIZE = 30

    /** 下一次从全量候选列表的哪个全局下标开始取 */
    private var candidateOffset = 0

    /** 全量候选列表是否已经取完（上一批不足 [CANDIDATE_PAGE_SIZE]） */
    private var candidatesExhausted = true

    var showComposition: String = "" // 候选词上方展示的拼音
    var preCommitText: String = "" // 待提交的文字
    private var customPhraseSize: Int = 0 // 自定义引擎候选词长度
    const val MASK_CASE_LOWER = 0

    /** 自定义候选（用户词库 / 常用语 / 日期联想）在候选栏上的标记，点击时直接提交文本 */
    const val CUSTOM_TAG = "📋"

    /**
     * 拼音纠错候选的标记（设计文档 `docs/pinyin-correction-design.md`）。
     * 与 [CUSTOM_TAG] 一样走「直接提交文本」的通道：纠错候选的编码和 Rime 当前组合串不同，
     * 不能交给 Rime 选词。候选栏只会在词的右上角补一个很小的 `?`（`CandidatesBarAdapter`）。
     */
    const val CORRECTION_TAG = "?"

    /**
     * 剔除"编码本身"后若已无其它候选，是否保留它作为唯一可上屏项。
     * i / u / v 这类编码本身没有汉字候选，删掉就什么都不剩了。
     */
    private const val KEEP_ECHO_WHEN_ALONE = true

    private var charCase = 0x0000

    /** 延迟纠错：连续按键时不断取消上一次，停下后再算（见 [scheduleCorrection]） */
    private val correctionHandler = Handler(Looper.getMainLooper())
    private var pendingCorrection: Runnable? = null

    /**
     * 停顿多久之后才做一次纠错。
     *
     * 判定已经改成纯内存查引擎词库（0.2 ms），剩下的只有对**已胜出编码**的那一次影子查询。
     * 16 ms 约等于一帧：连续打字的相邻按键会互相取消，停顿后结果几乎立刻出现，
     * 不再有此前 60 ms 那种「慢半拍」的割裂感。
     */
    private const val CORRECTION_DELAY_MS = 16L

    /** 引擎是否已经启动过：Rime.startup 是整套引擎装配，重复调用会让按键明显停顿 */
    private var engineStarted = false

    /**
     * 一次输入过程中用户选过的候选，用于判断是否属于"手动纠正"并落库到用户词库。
     * code 取第一次选择前的组合串（Rime 逐段消费组合串，第一次的快照天然就是整串编码）。
     */
    private class PendingSelect(val code: String, val text: String, val isFirstReal: Boolean)
    private val pendingSelects = mutableListOf<PendingSelect>()

    fun init() {
        Rime.getInstance(false)
        engineStarted = true
    }

    fun selectSchema(mod: String): Boolean {
        keyRecordStack.clear()
        charCase = MASK_CASE_LOWER
        if (!engineStarted) {
            Rime.startup(Launcher.instance.context, false)
            engineStarted = true
        } else if (Rime.getCurrentRimeSchema() == mod) {
            // 已经在这个方案上（例如反复点「中/EN」「123」再切回来），不必再切一次词典
            return true
        }
        // 换方案等于换词典，影子查询的缓存结果不再可信
        CorrectionSession.reset()
        return Rime.selectSchema(mod)
    }

    fun getCurrentRimeSchema(): String {
        return Rime.getCurrentRimeSchema()
    }

    /**
     * 是否输入完毕
     */
    fun isFinish(): Boolean {
        return keyRecordStack.isEmpty()
    }

    fun onNormalKey(event: KeyEvent) {
        val keyCode = event.keyCode
        val keyChar = if(keyCode == KeyEvent.KEYCODE_APOSTROPHE) if(isFinish()) '/'.code else '\''.code
            else event.unicodeChar
        if (keyRecordStack.pushKey(event))Rime.processKey(keyChar, event.action)
        updateCandidatesOrCommitText()
    }

    fun onDeleteKey() {
        keyRecordStack.processDelAction()
        updateCandidatesOrCommitText()
    }

    fun selectCandidate(index: Int): String? {
        cancelCorrection()
        val picked = showCandidates.getOrNull(index) ?: return null
        val rimeIndex = rimeIndexes.getOrNull(index) ?: -1
        if (rimeIndex < 0) return null // 自定义候选由 InputView 直接提交，不进 Rime
        val code = Rime.compositionText
        Rime.selectCandidate(rimeIndex)
        keyRecordStack.pushCandidateSelectAction()
        if (code.isNotBlank() && picked.text.isNotBlank()) {
            pendingSelects.add(PendingSelect(code, picked.text, index == customPhraseSize))
        }
        return updateCandidatesOrCommitText()
    }

    /**
     * 从全量候选列表里取一段，并同步推进分页游标。
     *
     * [offset] 是 librime 的候选全局下标，返回的第 i 项对应下标 `offset + i`；
     * 返回条数不足 [CANDIDATE_PAGE_SIZE] 即说明后面没有了。
     */
    private fun fetchCandidates(offset: Int): List<CandidateListItem> {
        val batch = Rime.getCandidates(offset, CANDIDATE_PAGE_SIZE)
        candidateOffset = offset + batch.size
        candidatesExhausted = batch.size < CANDIDATE_PAGE_SIZE
        return batch
    }

    /** 按当前大小写状态改写候选文本（英文方案下的 Shift / CapsLock 输出） */
    private fun applyCharCase(items: List<CandidateListItem>) {
        when (charCase) {
            KeyEvent.META_SHIFT_ON -> items.forEach { item ->
                item.text = item.text.lowercase().replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
            }
            KeyEvent.META_CAPS_LOCK_ON -> items.forEach { item -> item.text = item.text.uppercase() }
            else -> items.forEach { item -> item.text = item.text.lowercase() }
        }
    }

    fun selectPinyin(index: Int) {
        val pinyinKey = keyRecordStack.pushPinyinSelectAction(pinyins[index]) ?: return
        Rime.replaceKey(pinyinKey.posInInput, pinyinKey.t9Keys().length, pinyinKey.pinyin())
        updateCandidatesOrCommitText()
    }

    /**
     * 追加下一页候选，并同步维护 [rimeIndexes]（去重、索引映射都在这里收口，避免调用方各管一半）。
     *
     * 旧实现合成 `Page_Down` 键翻页，再把**页内偏移**当索引记下来，翻到第二页之后选词会落回
     * 第一页的同位候选（`menu/page_size: 100`，第 101 项起必现）。现在取的是全量列表的全局下标，
     * 选词原样交给 `select_candidate`，页/全局两套语义只剩一套。
     */
    fun appendNextPage(): Int {
        if (candidatesExhausted) return 0
        val base = candidateOffset
        val page = fetchCandidates(base)
        if (page.isEmpty()) return 0
        applyCharCase(page)
        val existing = HashSet<String>(showCandidates.size + page.size)
        showCandidates.forEach { existing.add(it.text.lowercase(Locale.ROOT)) }
        val addItems = ArrayList<CandidateListItem>(page.size)
        val addIndexes = ArrayList<Int>(page.size)
        page.forEachIndexed { i, item ->
            if (existing.add(item.text.lowercase(Locale.ROOT))) {
                addItems.add(item)
                addIndexes.add(base + i)
            }
        }
        if (addItems.isEmpty()) return 0
        showCandidates = showCandidates + addItems
        rimeIndexes = rimeIndexes + addIndexes
        return addItems.size
    }

    fun predictAssociationWords(text: String) {
        pinyins = emptyArray()
        if (text.isNotEmpty()) {
            showCandidates = buildList {
                val words = Rime.getAssociateList(text)
                val firstFive = words.take(5)
                addAll(firstFive.filterNotNull().map { CandidateListItem("", it) })
                addAll(CustomEngine.predictAssociationWordsChinese(text).map { CandidateListItem("", it) })
                val remaining = words.drop(5)
                addAll(remaining.filterNotNull().map { CandidateListItem("", it) })
            }.distinctBy { it.text.lowercase(Locale.ROOT) }
            rimeIndexes = IntArray(showCandidates.size) { -1 } // 联想词不走 selectCandidate
            candidateOffset = 0
            candidatesExhausted = true
            showComposition = ""
        }
    }

    fun selectAssociation(index: Int) {
        val indexReal = index - customPhraseSize
        Rime.chooseAssociate(indexReal)
        updateCandidatesOrCommitText()
        preCommitText = showCandidates.getOrNull(indexReal)?.text?:""
    }

    fun reset() {
        cancelCorrection()
        showCandidates = emptyList()
        rimeIndexes = IntArray(0)
        customPhraseSize = 0
        candidateOffset = 0
        candidatesExhausted = true
        pinyins = emptyArray()
        showComposition = ""
        preCommitText = ""
        pendingSelects.clear()
        keyRecordStack.clear()
        Rime.clearComposition()
        CorrectionSession.reset()
        if(charCase == KeyEvent.META_SHIFT_ON) charCase = MASK_CASE_LOWER
    }

    /**
     * 一次输入结束（Rime 给出 commit）时结算：整次输入里只要选过非首位候选，就记为用户纠正。
     * 词的合法性、长度、去重、防抖都在 UserDictManager 里做。
     */
    private fun flushLearning() {
        if (pendingSelects.isEmpty()) return
        val code = pendingSelects.first().code
        val word = pendingSelects.joinToString("") { it.text }
        // 自定义候选会被剔重/插在最前，所以"是不是首位"在选词时就按展示位置算好了
        val corrected = pendingSelects.any { !it.isFirstReal }
        pendingSelects.clear()
        if (!corrected) return
        UserDictManager.learn(code, word, Rime.getCurrentRimeSchema())
    }

    /** 走自定义候选（📋）直接提交时不经过 Rime 选词，清掉待结算记录避免串到下一次输入 */
    fun clearPendingLearning() {
        pendingSelects.clear()
    }

    /**
     * 用户选中了纠错候选（≈）：把「打错的编码 -> 正确词」记进用户词库。
     * 这是离线可做的自适应闭环——下次同样的错码直接命中，不用再算一遍纠错。
     * 是否真的落库由「自动学习纠正的词」开关和 `UserDictManager` 的合法性检查决定。
     */
    fun learnCorrection(text: String) {
        val code = CorrectionSession.lastRawCode
        if (code.isBlank() || text.isBlank()) return
        UserDictManager.learn(code, text, Rime.getCurrentRimeSchema())
    }

    fun destroy() {
        cancelCorrection()
        engineStarted = false
        Rime.destroy()
    }

    /** 引擎是否仍处于组合态。与"候选是否为空"不同：i / u / v 这类编码有编码、没候选。 */
    val hasComposition: Boolean
        get() = Rime.compositionText.isNotEmpty()

    /** Rime 原始编码串（未做展示处理），用于上屏编码兜底 */
    val compositionText: String
        get() = Rime.compositionText

    /** 英文方案依赖"原字母"候选做补全，这里整段跳过 */
    private fun shouldHideEcho(): Boolean =
        !InputModeSwitcher.isEnglish && Rime.getCurrentRimeSchema() != CustomConstant.SCHEMA_EN

    /**
     * 定位"回声候选"：第一条与编码相同的纯字母候选（忽略大小写、忽略分隔符）。
     * 注意必须忽略大小写：Rime 会把输入码规范化成大写回显（输入 h，echo 候选是 "H"），
     * 而组合串保持小写。实测 echo 候选恒在列表首位，所以只取第一条命中，后面的同名
     * 真实词条（如英文 I / US）不会被误删。
     * @return 命中项在 Rime 候选数组里的下标，-1 表示没有
     */
    private fun findEchoIndex(candidates: List<CandidateListItem>, composition: String): Int {
        val code = composition.replace("'", "").replace(" ", "")
        if (code.isEmpty() || code.any { it !in 'a'..'z' && it !in 'A'..'Z' }) return -1
        return candidates.indexOfFirst {
            it.text.trim().replace("'", "").equals(code, ignoreCase = true)
        }
    }

    private fun updateCandidatesOrCommitText(): String? {
        val rimeCommit = Rime.getRimeCommit()
        if (rimeCommit != null) {
            cancelCorrection()
            flushLearning()
            keyRecordStack.clear()
            preCommitText = rimeCommit.commitText
            preCommitText = if (charCase == KeyEvent.META_SHIFT_ON) {
                preCommitText.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            } else if (charCase == KeyEvent.META_CAPS_LOCK_ON) {
                preCommitText.uppercase()
            } else {
                preCommitText.lowercase()
            }
            showComposition = ""
            showCandidates = emptyList()
            rimeIndexes = IntArray(0)
            candidateOffset = 0
            candidatesExhausted = true
            return preCommitText
        }
        // 组合串直接读 Rime 缓存的那份：所有会改引擎状态的调用（processKey / replaceKey /
        // selectCandidate / clearComposition / selectSchema）内部都已经刷新过；
        // pushKey 返回 false 的那条路径引擎状态本来就没变。这里不再多拉一次 get_context。
        val candidates = fetchCandidates(0)
        customPhraseSize = 0
        val compositionText = Rime.compositionText
        // 定位"编码本身"候选（echo_translator 的产物）；只有还存在别的候选时才剔掉它，
        // 否则 i / u / v 这类本身没有汉字候选的编码会连一个可上屏的项都不剩。
        val echoIndex = if (shouldHideEcho()) findEchoIndex(candidates, compositionText) else -1
        val hideEcho = echoIndex >= 0 && (candidates.size > 1 || !KEEP_ECHO_WHEN_ALONE)
        showCandidates = when {
            compositionText.isNotBlank() -> {
                val phrase = CustomEngine.processPhrase(compositionText.replace("\'", ""))
                if(InputModeSwitcher.isEnglish && StringUtils.isLetter(compositionText) &&
                    candidates.isNotEmpty() &&
                    !compositionText.equals(candidates.first().text, ignoreCase = true) ){
                    phrase.add(0, compositionText)
                }
                // 自定义候选在前、自带词库在后；相同文本次只保留第一条。
                // 同一次输入里注入词与自带词库可能给出同一个词，重复的候选没有意义。
                val merged = ArrayList<CandidateListItem>(phrase.size + candidates.size)
                val indexes = ArrayList<Int>(phrase.size + candidates.size)
                val seen = HashSet<String>(phrase.size + candidates.size)
                phrase.forEach { text ->
                    if (seen.add(text.lowercase(Locale.ROOT))) {
                        merged.add(CandidateListItem(CUSTOM_TAG, text))
                        indexes.add(-1)
                    }
                }
                customPhraseSize = merged.size
                candidates.forEachIndexed { rawIndex, item ->
                    if (hideEcho && rawIndex == echoIndex) return@forEachIndexed
                    if (seen.add(item.text.lowercase(Locale.ROOT))) {
                        merged.add(item)
                        indexes.add(rawIndex)
                    }
                }
                rimeIndexes = indexes.toIntArray()
                merged
            }
            else -> {
                rimeIndexes = IntArray(candidates.size) { it }
                candidates
            }
        }
        var count = Rime.compositionText.count { it in 'A'..'Z' }
        if (count > 0) {
            keyRecordStack.forEachReversed { inputKey ->
                if (inputKey is InputKey.T9Key) inputKey.consumed = count-- <= 0
            }
        }
        var composition = getCurrentComposition(candidates)
        when (charCase) {
            KeyEvent.META_SHIFT_ON -> {
                for (item in showCandidates) item.text = item.text.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                composition = composition.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            KeyEvent.META_CAPS_LOCK_ON -> {
                for (item in showCandidates) item.text = item.text.uppercase()
                composition = composition.uppercase()
            }
            else -> {
                for (item in showCandidates) item.text = item.text.lowercase()
                composition = composition.lowercase()
            }
        }
        val rimeSchema = Rime.getCurrentRimeSchema()
        pinyins = when (rimeSchema) {
            CustomConstant.SCHEMA_ZH_T9 -> {
                T9PinYinUtils.t9KeyToPinyin(Regex("[A-Z]+").find(compositionText.replace("'", ""))?.value ?: "")
            }
            CustomConstant.SCHEMA_ZH_DOUBLE_LX17 -> {
                LX17PinYinUtils.lx17KeyToPinyin(Regex("[A-Z]+").find(compositionText.replace("'", ""))?.value ?: "")
            }
            else -> {
                emptyArray()
            }
        }
        showComposition = composition
        preCommitText = ""
        // 判定已改成读引擎词库（0.2 ms），只剩「取文本」那一次影子查询还有成本，
        // 所以这里只做一帧的去抖：连打时合并，停下后立刻出结果。
        scheduleCorrection(compositionText, candidates)
        return null
    }

    /**
     * 安排一次延迟纠错。任何一次新的按键都会取消上一次待执行的纠错，
     * 因此连续输入时只有停下来那一刻才会真正去查。
     */
    private fun scheduleCorrection(
        compositionText: String,
        candidates: List<CandidateListItem>,
    ) {
        cancelCorrection()
        if (compositionText.isBlank()) return
        if (!AppPrefs.getInstance().input.pinyinCorrectionEnable.getValue()) return
        val task = Runnable {
            pendingCorrection = null
            // 停顿期间又按了键、选了词、切换了方案：这次纠错的上下文已经失效
            if (Rime.compositionText != compositionText) return@Runnable
            if (showCandidates.isEmpty()) return@Runnable
            if (!applyPinyinCorrection(compositionText, candidates)) return@Runnable
            // 纠错候选补进列表后主动推一次，界面自己会刷新
            DictDecoder.candidatesLiveData.value = showCandidates
        }
        pendingCorrection = task
        correctionHandler.postDelayed(task, CORRECTION_DELAY_MS)
    }

    private fun cancelCorrection() {
        pendingCorrection?.let { correctionHandler.removeCallbacks(it) }
        pendingCorrection = null
    }

    /**
     * 把纠错候选插进 [showCandidates]。
     *
     * - 置前：Rime 一个真实候选都没有；
     * - 插在首个候选之后：不改变原来的首位，又保证用户看得见；
     * - 追加到最后：合法输入的模糊音扩展。
     *
     * 「提升」语义：如果纠错词在 Rime 的候选里本来就存在（`beijingg` 的「北京」就在第 5 位），
     * 会把它从原位置删掉、换成带标记的副本放到纠错位置，否则用户会觉得「纠错没生效」。
     * 插入项在 [rimeIndexes] 里记 -1，选词由 `InputView` 直接提交文本，不进 Rime。
     *
     * @return 是否真的改写了候选列表（供调用方决定要不要刷新界面）
     */
    private fun applyPinyinCorrection(
        compositionText: String,
        rimeCandidates: List<CandidateListItem>,
    ): Boolean {
        // 输入连干净切分都做不到（`zlvlnv`、`lsogon`），说明 Rime 的整条候选链都建立在
        // 错误切分上：它会把每个字母单独当音节，再逐音节各挑一个高频字，拼出覆盖全文的
        // 「整句」（之旅靓女、来说讴歌欧帖）。这类串不在词库里，先把它们沉到底下。
        var changed = !PinyinCorrector.hasCleanParse(compositionText) && demoteUnverifiedCandidates()

        val correction = CorrectionSession.correct(
            compositionText,
            rimeCandidates,
            showCandidates.map { it.text },
        )
        if (correction == null || correction.texts.isEmpty()) return changed

        val items = correction.texts.map { CandidateListItem(CORRECTION_TAG, it) }
        val at = when (correction.placement) {
            CorrectionSession.Placement.FRONT -> 0
            CorrectionSession.Placement.AFTER_FIRST ->
                // 跳过用户词库注入的自定义候选，但保留第一个 Rime 候选在原位
                (customPhraseSize + 1).coerceAtMost(showCandidates.size)
            CorrectionSession.Placement.TAIL -> showCandidates.size
        }
        // 模糊音追加在最后时不删重（末尾重复无伤大雅），纠错则要把原有同词提上来
        val dedupe = correction.placement != CorrectionSession.Placement.TAIL
        val promoted = if (dedupe) correction.texts.map { it.lowercase(Locale.ROOT) }.toHashSet() else emptySet()

        val headItems = showCandidates.take(at)
        val headIndexes = rimeIndexes.take(at)
        val tailItems = ArrayList<CandidateListItem>(showCandidates.size - at)
        val tailIndexes = ArrayList<Int>(rimeIndexes.size - at)
        for (i in at until showCandidates.size) {
            val item = showCandidates[i]
            if (item.text.lowercase(Locale.ROOT) in promoted) continue
            tailItems.add(item)
            tailIndexes.add(rimeIndexes[i])
        }

        showCandidates = headItems + items + tailItems
        val mergedIndexes = ArrayList<Int>(headIndexes.size + items.size + tailIndexes.size)
        mergedIndexes.addAll(headIndexes)
        repeat(items.size) { mergedIndexes.add(-1) }
        mergedIndexes.addAll(tailIndexes)
        rimeIndexes = mergedIndexes.toIntArray()
        // 插到自定义候选之前时，首位判定要跟着挪
        if (at <= customPhraseSize) customPhraseSize += items.size
        return true
    }

    /**
     * 把词库里查不到的候选沉到后面（稳定排序，两组内部都保持 Rime 原来的相对顺序）。
     *
     * 用户词库注入的自定义候选（前 [customPhraseSize] 条）不参与，它们本来就不在 `table.bin` 里。
     *
     * @return 顺序是否真的变了
     */
    private fun demoteUnverifiedCandidates(): Boolean {
        val from = customPhraseSize.coerceAtMost(showCandidates.size)
        val size = showCandidates.size - from
        if (size < 2) return false
        val order = (0 until size).sortedBy {
            if (CorrectionSession.isVerifiedWord(showCandidates[from + it].comment)) 0 else 1
        }
        if (order.indices.all { order[it] == it }) return false

        showCandidates = showCandidates.take(from) + order.map { showCandidates[from + it] }
        rimeIndexes = rimeIndexes.copyOfRange(0, from) +
            IntArray(size) { rimeIndexes[from + order[it]] }
        return true
    }

    /**
     * 拿到候选词拼音组合
     */
    fun getPrefixs(): Array<String> {
        return pinyins
    }

    private fun getCurrentComposition(candidates: List<CandidateListItem>): String {
        val composition = Rime.compositionText
        val rimeSchema = Rime.getCurrentRimeSchema()
        if(rimeSchema == CustomConstant.SCHEMA_EN) return ""
        if(composition.isEmpty()) return ""
        if(candidates.isEmpty()) return composition
        val comment = candidates.first().comment
        val result =  when {
            comment.isNotBlank() && comment.startsWith("~") -> composition
            rimeSchema == CustomConstant.SCHEMA_ZH_T9 -> {
                T9PinYinUtils.getT9Composition(composition, comment)
            }
            rimeSchema.startsWith(CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY) -> {
                if(!AppPrefs.getInstance().keyboardSetting.keyboardDoubleInputKey.getValue()) composition
                else DoublePinYinUtils.getDoublePinYinComposition(rimeSchema, composition, comment)
            }
            else -> {
                QwertyPinYinUtils.getQwertyComposition(composition, comment)
            }
        }
        return if (!composition.endsWith("'") && result.endsWith("'")) result.dropLast(1) else result
    }

    /**
     * 设置输入法搜索参数
     */
    fun setImeOption(option: String, value: Boolean) {
        Rime.setOption(option, value)
    }

    fun setCharCase(charCase: Int) {
        this.charCase = charCase
    }

}