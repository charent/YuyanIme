package com.yuyan.imemodule.keyboard

import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.inputmethod.HWEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 键盘类型与「键盘缓存失效」的唯一状态源。
 *
 * Phase 5 之后不再持有任何 View / Container：旧实现的 `mInputView`、`keyboards` 缓存、
 * `currentContainer` 全部删除，只保留两路状态供 Compose 订阅：
 *
 * - [keyboardType]：要显示哪套键盘（输入 / 符号 / 设置 / 候选 / 剪贴板 …）；
 * - [revision]：`clearKeyboard()` 的失效计数，Any 处改了尺寸、主题、键位、侧边栏数据后
 *   只要调用 `clearKeyboard()`，键盘就会重新从 `KeyboardLoaderUtil` 取软键盘数据。
 */
class KeyboardManager {

    enum class KeyboardType {
        T9, QWERTY, LX17, QWERTYABC, NUMBER, SYMBOL, SETTINGS, HANDWRITING, CANDIDATES, ClipBoard, TEXTEDIT
    }

    private val _keyboardType = MutableStateFlow<KeyboardType?>(null)
    val keyboardType: StateFlow<KeyboardType?> = _keyboardType.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** 丢弃键盘缓存（键帽数据、尺寸、主题等变化后调用） */
    fun clearKeyboard() {
        _revision.value = _revision.value + 1
    }

    fun switchKeyboard(layout: Int = InputModeSwitcher.skbLayout) {
        val keyboardName = when (layout) {
            0x1000 -> KeyboardType.QWERTY
            0x4000 -> KeyboardType.QWERTYABC
            // 手写引擎不可用时（16KB 页设备）不允许停留在手写模式：
            // 上次退出时若正处在手写模式，恢复时会走到这里
            0x3000 -> if (HWEngine.isAvailable) KeyboardType.HANDWRITING else KeyboardType.QWERTY
            0x5000 -> KeyboardType.NUMBER
            0x6000 -> KeyboardType.LX17
            0x8000 -> KeyboardType.TEXTEDIT
            else -> KeyboardType.T9
        }
        switchKeyboard(keyboardName)
    }

    fun switchKeyboard(keyboardName: KeyboardType) {
        _keyboardType.value = keyboardName
    }

    val currentType: KeyboardType?
        get() = _keyboardType.value

    /** 当前是否停留在「可以输入字符」的键盘上（符号/设置/候选/剪贴板都不算） */
    val isInputKeyboard: Boolean
        get() = when (currentType) {
            KeyboardType.SYMBOL, KeyboardType.SETTINGS, KeyboardType.CANDIDATES,
            KeyboardType.ClipBoard, null -> false
            else -> true
        }

    companion object {
        private var mInstance: KeyboardManager? = null
        @JvmStatic
        val instance: KeyboardManager
            get() {
                if (null == mInstance) {
                    mInstance = KeyboardManager()
                }
                return mInstance!!
            }
    }
}
