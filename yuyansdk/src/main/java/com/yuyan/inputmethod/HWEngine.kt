package com.yuyan.inputmethod

import android.util.Log
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.callback.IHandWritingCallBack
import com.yuyan.imemodule.libs.pinyin4j.PinyinHelper
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinCaseType
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinOutputFormat
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinToneType
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinVCharType
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.core.HandWriting

object HWEngine {

    private const val TAG = "HWEngine"

    private var mHanyuPinyinOutputFormat: HanyuPinyinOutputFormat

    /**
     * 手写引擎是否可用。
     *
     * 为 false 的两种情形：
     * 1. 16KB 页设备上手写 .so 仅 4KB 对齐，dlopen 失败（见 [NativePageCompat]）；
     * 2. 词库目录未就绪等初始化失败。
     *
     * 调用方必须在展示手写入口 / 执行识别之前判这个值，否则会崩。
     */
    val isAvailable: Boolean

    init {
        mHanyuPinyinOutputFormat = HanyuPinyinOutputFormat()
        mHanyuPinyinOutputFormat.caseType = HanyuPinyinCaseType.LOWERCASE
        mHanyuPinyinOutputFormat.toneType = HanyuPinyinToneType.WITH_TONE_MARK
        mHanyuPinyinOutputFormat.vCharType = HanyuPinyinVCharType.WITH_U_UNICODE

        // 历史实现直接调用 HandWriting.init 并把 Boolean 结果丢弃，
        // 加载失败时会一路抛到 object 初始化，最终 ExceptionInInitializerError。
        isAvailable = runCatching {
            HandWriting.ensureLoaded() &&
                    HandWriting.init(Launcher.instance.context) &&
                    HandWriting.setProperties() &&
                    HandWriting.selectInputMode(5)
        }.onFailure {
            Log.w(TAG, "手写引擎初始化失败，手写输入将被禁用", it)
        }.getOrDefault(false)
    }

    fun recognitionData(strokes: MutableList<Short?>, recogResult: IHandWritingCallBack) {
        if (!isAvailable) return
        HandWriting.resetStrokes()
        val intArray = strokes.filterNotNull().map { it.toInt() }.toIntArray()
        if (!HandWriting.submitPoints(intArray)) return
        val candidatesPyComposition = runCatching { HandWriting.getCandidatesPyComposition() }
            .getOrElse {
                Log.w(TAG, "手写识别失败", it)
                return
            }
        val candidates = candidatesPyComposition.getOrNull(0) ?: return
        if (candidates.isEmpty()) return
        val recogResultItems = ArrayList<CandidateListItem>(candidates.size)
        for (candidate in candidates) {
            if (candidate == null) continue
            recogResultItems.add(
                CandidateListItem(
                    PinyinHelper.toHanYuPinyin(candidate, mHanyuPinyinOutputFormat, "'")
                        .ifEmpty { candidate },
                    candidate
                )
            )
        }
        if (recogResultItems.isNotEmpty()) recogResult.onSucess(recogResultItems.toTypedArray())
    }
}
