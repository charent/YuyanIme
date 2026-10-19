package com.yuyan.imemodule.prefs.behavior

import com.yuyan.imemodule.view.preference.ManagedPreference

/**
 * 纠错力度：直接映射成 [com.yuyan.inputmethod.correction.PinyinCorrector] 的总代价预算。
 *
 * 预算越大，能接受的编辑越多（召回更高，但也更容易把「看起来差得远」的编码纳入进来）。
 * 三档取值来自设计文档 4.7，默认用「标准」，也就是原型评测时用的 2.2。
 */
enum class PinyinCorrectionStrength {
    conservative,
    standard,
    aggressive;

    fun budget(): Float = when (this) {
        conservative -> 1.6f
        standard -> 2.2f
        aggressive -> 3.0f
    }

    companion object : ManagedPreference.StringLikeCodec<PinyinCorrectionStrength> {
        override fun decode(raw: String) = PinyinCorrectionStrength.valueOf(raw)
    }
}
