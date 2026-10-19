package com.yuyan.imemodule.adapter

import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan

/**
 * 纠错候选的视觉标记：在词的右上角加一个很小的 `?`（上标 + 缩小字号）。
 *
 * 不用前缀符号（原来的 `≈`）是为了不把词本身推歪、也不喧宾夺主——
 * 纠错候选只是「你可能是想打这个」，主候选不该被符号抢走注意力。
 * 只影响显示，上屏用的仍然是 [com.yuyan.inputmethod.core.CandidateListItem.text]。
 */
internal fun markCorrection(text: String): CharSequence {
    val mark = "?"
    val span = SpannableString(text + mark)
    val start = text.length
    val end = start + mark.length
    span.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    span.setSpan(RelativeSizeSpan(0.55f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return span
}
