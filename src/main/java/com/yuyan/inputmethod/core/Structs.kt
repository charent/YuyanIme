package com.yuyan.inputmethod.core

import androidx.annotation.Keep

@Keep
data class SchemaItem(
    val id: String,
    val name: String = "",
)

@Keep
data class CandidateListItem(
    var comment: String,
    var text: String,
)

@Keep
data class RimeComposition(
    val length: Int = 0,
    val cursorPos: Int = 0,
    val selStart: Int = 0,
    val selEnd: Int = 0,
    val preedit: String? = "",
)

/**
 * 候选区元信息。
 *
 * **不再携带候选数组**：候选统一由 [Rime.getCandidates] 走 librime 的
 * `candidate_list_*` 全量列表提供，分批取、下标就是全局下标。
 * 这里保留 `numCandidates` 之类的字段只为判断「有没有菜单」。
 */
@Keep
data class RimeMenu(
    val pageSize: Int = 0,
    val pageNo: Int = 0,
    val isLastPage: Boolean = false,
    val highlightedCandidateIndex: Int = 0,
    val numCandidates: Int = 0,
)

@Keep
data class RimeCommit(
    val commitText: String = "",
)

@Keep
data class RimeContext(
    val composition: RimeComposition? = null,
    val menu: RimeMenu? = null,
    val commitTextPreview: String? = "",
    val selectLabels: Array<String> = arrayOf(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RimeContext

        if (composition != other.composition) return false
        if (menu != other.menu) return false
        if (commitTextPreview != other.commitTextPreview) return false
        if (!selectLabels.contentEquals(other.selectLabels)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = composition?.hashCode() ?: 0
        result = 31 * result + (menu?.hashCode() ?: 0)
        result = 31 * result + commitTextPreview.hashCode()
        result = 31 * result + selectLabels.contentHashCode()
        return result
    }
}

/** Rime狀態  */
@Keep
data class RimeStatus(
    val schemaId: String = "",
    val schemaName: String = "",
    val isDisable: Boolean = true,
    val isComposing: Boolean = false,
    val isAsciiMode: Boolean = true,
    val isFullShape: Boolean = false,
    val isSimplified: Boolean = false,
    val isTraditional: Boolean = false,
    val isAsciiPunch: Boolean = true,
)
