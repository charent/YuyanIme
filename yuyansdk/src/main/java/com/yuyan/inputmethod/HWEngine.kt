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
     * 1. 缺少对应 ABI 的手写 .so（x86_64 模拟器）或 16KB 页设备上 .so 仅 4KB 对齐，
     *    dlopen 失败（见 [NativePageCompat]）；
     * 2. 词库目录未就绪等初始化失败。
     *
     * 调用方必须在展示手写入口 / 执行识别之前判这个值，否则会崩。
     *
     * 惰性 + 可重试：初始化失败不缓存，下次查询会重试。首次触碰可能发生在
 * Launcher 后台线程拷贝 hw 词库完成之前（如 IME 恢复到上次的手写模式时
 * KeyboardManager 会先查这里），若失败被永久缓存，词库就绪后手写入口也
     * 永远不再出现。成功后 [initialized] 置位，后续查询零开销；
     * .so 加载失败本身会被 [HandWriting.ensureLoaded] 缓存，重试不会反复 dlopen。
     */
    @Volatile
    private var initialized: Boolean = false

    val isAvailable: Boolean
        get() {
            if (initialized) return true
            return synchronized(this) {
                if (initialized) return true
                runCatching {
                    HandWriting.ensureLoaded() &&
                            HandWriting.init(Launcher.instance.context) &&
                            HandWriting.setProperties() &&
                            HandWriting.selectInputMode(5)
                }.onFailure {
                    Log.w(TAG, "手写引擎初始化失败，手写输入将被禁用", it)
                }.getOrDefault(false).also { initialized = it }
            }
        }

    init {
        mHanyuPinyinOutputFormat = HanyuPinyinOutputFormat()
        mHanyuPinyinOutputFormat.caseType = HanyuPinyinCaseType.LOWERCASE
        mHanyuPinyinOutputFormat.toneType = HanyuPinyinToneType.WITH_TONE_MARK
        mHanyuPinyinOutputFormat.vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
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
