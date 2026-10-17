package com.yuyan.inputmethod

import android.view.KeyEvent
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.manager.UserDictManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.core.Rime
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
     */
    private var rimeIndexes: IntArray = IntArray(0)

    var showComposition: String = "" // 候选词上方展示的拼音
    var preCommitText: String = "" // 待提交的文字
    private var customPhraseSize: Int = 0 // 自定义引擎候选词长度
    const val MASK_CASE_LOWER = 0

    /** 自定义候选（用户词库 / 常用语 / 日期联想）在候选栏上的标记，点击时直接提交文本 */
    const val CUSTOM_TAG = "📋"

    /**
     * 剔除"编码本身"后若已无其它候选，是否保留它作为唯一可上屏项。
     * i / u / v 这类编码本身没有汉字候选，删掉就什么都不剩了。
     */
    private const val KEEP_ECHO_WHEN_ALONE = true

    private var charCase = 0x0000

    /**
     * 一次输入过程中用户选过的候选，用于判断是否属于"手动纠正"并落库到用户词库。
     * code 取第一次选择前的组合串（Rime 逐段消费组合串，第一次的快照天然就是整串编码）。
     */
    private class PendingSelect(val code: String, val text: String, val isFirstReal: Boolean)
    private val pendingSelects = mutableListOf<PendingSelect>()

    fun init() {
        Rime.getInstance(false)
    }

    fun selectSchema(mod: String): Boolean {
        keyRecordStack.clear()
        charCase = MASK_CASE_LOWER
        Rime.startup(Launcher.instance.context, false)
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

    fun getNextPageCandidates(): Array<CandidateListItem> {
        return if (Rime.hasRight()) {
            Rime.processKey(keyRecordStack.getRimeKeycodeByName("Page_Down"), 0)
           val candidates = Rime.getRimeContext()!!.candidates
            when (charCase) {
                KeyEvent.META_SHIFT_ON -> {
                    for (item in candidates) {
                        item.text = item.text.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    }
                }
                KeyEvent.META_CAPS_LOCK_ON -> {
                    for (item in candidates) {
                        item.text = item.text.uppercase()
                    }
                }
                else -> {
                    for (item in candidates) {
                        item.text = item.text.lowercase()
                    }
                }
            }
            candidates
        } else emptyArray()
    }

    fun selectPinyin(index: Int) {
        val pinyinKey = keyRecordStack.pushPinyinSelectAction(pinyins[index]) ?: return
        Rime.replaceKey(pinyinKey.posInInput, pinyinKey.t9Keys().length, pinyinKey.pinyin())
        updateCandidatesOrCommitText()
    }

    /**
     * 追加下一页候选，并同步维护 [rimeIndexes]（去重、索引映射都在这里收口，避免调用方各管一半）。
     * 注意：Rime 的候选下标是"页内偏移"，所以新页里的第 i 项对应索引就是 i。
     */
    fun appendNextPage(): Int {
        val page = getNextPageCandidates()
        if (page.isEmpty()) return 0
        val existing = HashSet<String>(showCandidates.size + page.size)
        showCandidates.forEach { existing.add(it.text.lowercase(Locale.ROOT)) }
        val addItems = ArrayList<CandidateListItem>(page.size)
        val addIndexes = ArrayList<Int>(page.size)
        page.forEachIndexed { pageIndex, item ->
            if (existing.add(item.text.lowercase(Locale.ROOT))) {
                addItems.add(item)
                addIndexes.add(pageIndex)
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
        showCandidates = emptyList()
        rimeIndexes = IntArray(0)
        customPhraseSize = 0
        pinyins = emptyArray()
        showComposition = ""
        preCommitText = ""
        pendingSelects.clear()
        keyRecordStack.clear()
        Rime.clearComposition()
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

    fun destroy() = Rime.destroy()

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
            return preCommitText
        }
        val candidates = Rime.getRimeContext()?.candidates?.asList() ?: emptyList()
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
        return null
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