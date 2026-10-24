package com.whale.imemodule.service

import com.whale.inputmethod.RimeEngine
import com.whale.inputmethod.core.CandidateListItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 词库解码操作对象。
 *
 * Phase 5 起状态只有一条通道：不可变快照 [candidateState]。
 * 旧的 `candidatesLiveData` 与其在 `ImeService` 里的 `observeForever` 已删除，
 * 取值统一走 [candidates]，避免两套状态出现短暂不一致。
 */
object DictDecoder {

    var activeCandidate = 0  //当前显示候选词位置
    var activeCandidateBar = 0  //当前显示候选词位置

    /**
     * 候选快照。
     *
     * @param generation 整表替换时 +1，追加时不变（视图据此区分「重绑」和「增量插入」）
     * @param prefixRevision 拼音前缀（T9 左侧候选拼音）变化时 +1
     */
    data class CandidateState(
        val candidates: List<CandidateListItem> = emptyList(),
        val generation: Int = 0,
        val composingText: String = "",
        val isAssociate: Boolean = false,
        val prefixRevision: Int = 0,
    )

    private val _candidateState = MutableStateFlow(CandidateState())
    val candidateState: StateFlow<CandidateState> = _candidateState.asStateFlow()

    // 是否是联想词
    var isAssociate = false

    /**
     * 候选列表的"代"：**整表替换**时 +1，**追加**时不变。
     *
     * 视图据此区分两种刷新：代变了说明是新的一次输入（可以全量重绑、滚回顶部）；
     * 代没变但条数变多，说明只是往后追加了一批，应该 `notifyItemRangeInserted`
     * ——`notifyDataSetChanged` 会重绑所有可见项并打断正在进行的滑动，滚到边界时每加一批就卡一下。
     */
    var generation: Int = 0
        private set

    private fun publish(list: List<CandidateListItem>) {
        generation++
        _candidateState.value = CandidateState(
            candidates = list,
            generation = generation,
            composingText = runCatching { RimeEngine.showComposition }.getOrDefault(""),
            isAssociate = isAssociate,
            prefixRevision = _candidateState.value.prefixRevision,
        )
    }

    /** 只追加、不换代：候选被追加一批（翻页预取） */
    private fun publishAppend(list: List<CandidateListItem>) {
        _candidateState.value = _candidateState.value.copy(
            candidates = list,
            composingText = runCatching { RimeEngine.showComposition }.getOrDefault(""),
            isAssociate = isAssociate,
        )
    }

    private fun bumpPrefix() {
        _candidateState.value = _candidateState.value.copy(
            prefixRevision = _candidateState.value.prefixRevision + 1,
            composingText = runCatching { RimeEngine.showComposition }.getOrDefault(""),
        )
    }

    /**
     * 重置候选词
     */
    fun reset() {
        isAssociate = false
        activeCandidate = 0
        activeCandidateBar = 0
        publish(emptyList())
    }

    val candidates: List<CandidateListItem>
        get() = _candidateState.value.candidates

    val isCandidatesEmpty: Boolean
        get() = candidates.isEmpty()

    val candidateSize: Int
        get() = candidates.size

    // 增加拼写字符，重置候选词状态
    fun inputAction() {
        activeCandidate = 0
        activeCandidateBar = 0
        isAssociate = false
    }

    /**
     * 选择拼音
     * @param position 选择的position
     */
    fun selectPrefix(position: Int) {
        activeCandidate = 0
        activeCandidateBar = 0
        bumpPrefix()
    }

    val prefixs: Array<String>  //获取拼音组合
        get() = RimeEngine.getPrefixs()

    /**
     * 删除
     */
    fun deleteAction() {
        activeCandidate = 0
        activeCandidateBar = 0
    }

    val composingStrForDisplay: String   //获取显示的拼音字符串/
        get() = RimeEngine.showComposition

    val composingStrForCommit: String   // 获取输入的拼音字符串
        get() = RimeEngine.showComposition.replace("'", "")
            .ifEmpty { RimeEngine.compositionText }        // 有编码但没候选时（i/u/v）也要能按编码上屏
            .ifEmpty { getCandidate(0)?.text ?: "" }

    /**
     * 引擎是否仍在组合态。与 [isCandidatesEmpty] 不同：
     * i / u / v 这类编码会出现"有编码、没候选"，此时不能当成"没在输入"把组合串清掉。
     */
    val isComposing: Boolean
        get() = RimeEngine.hasComposition

    val nextPageCandidates: Int   // 获取下一页的候选词
        get() {
            val fresh = RimeEngine.appendNextPage()
            // 追加不算换"代"：视图会走增量插入，不重绑、不动滚动位置
            if (fresh > 0) publishAppend(RimeEngine.showCandidates)
            return fresh
        }

    /**
     * 选择一个候选词，且重新获取候选词列表
     */
    fun chooseDecodingCandidate(candId: Int): String {
        activeCandidate = 0
        activeCandidateBar = 0
        var candidate: String
        if (!InputDispatcher.isFinish || isAssociate) { // Rime和联想
            if (candId >= 0) InputDispatcher.getWordSelectedWord(candId)
            val newCandidates = RimeEngine.showCandidates
            candidate = if (newCandidates.isNotEmpty()) RimeEngine.preCommitText
            else if (candId in 0..<candidateSize) RimeEngine.preCommitText.ifEmpty { candidates[candId].text }
            else ""
            publish(newCandidates)
        } else {  // 手写
            candidate = if (candId in 0..<candidateSize) candidates[candId].text else ""
            reset()
        }
        return candidate
    }

    /**
     * 对输入的拼音进行查询。
     */
    fun updateDecodingCandidate() {
        activeCandidate = 0
        activeCandidateBar = 0
        publish(RimeEngine.showCandidates)
    }

    /**
     * 候选内容被就地改写（插入/提升纠错候选、按可信度重排）：按"整表替换"发布。
     * 这类改动可能落在列表中间，不能当作"追加"去做增量插入。
     */
    fun notifyCandidatesChanged() {
        publish(RimeEngine.showCandidates)
    }

    /**
     * 获得指定的候选词
     */
    fun getCandidate(candId: Int): CandidateListItem? = candidates.getOrNull(candId)

    // 更新候选词
    fun cacheCandidates(words: Array<CandidateListItem>, associate: Boolean = false) {
        isAssociate = associate
        activeCandidate = 0
        activeCandidateBar = 0
        publish(words.asList())
    }

    /**
     * 根据输入的字符查询候选词
     */
    fun getAssociateWord(words: String) {
        isAssociate = true
    }
}
