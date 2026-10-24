package com.whale.imemodule.prefs

import androidx.annotation.StringRes
import com.whale.imemodule.prefs.ManagedPreference.StringLikeCodec

/**
 * 偏好项的 **UI 元数据**（方案 §2.4）。
 *
 * Phase 4 起这里不再持有任何 `androidx.preference` 类型，也不再负责创建 View：
 * 它只是「怎么描述这一行」的纯数据，由 `SettingsRenderer` 翻译成 Compose 控件。
 * 数据侧（取值/写值/变更通知）仍是 [ManagedPreference]，两层完全解耦。
 */
abstract class ManagedPreferenceUi(
    val key: String,
    private val enableUiOn: (() -> Boolean)? = null
) {

    fun isEnabled() = enableUiOn?.invoke() ?: true

    /** 分组标题 */
    class Category(
        @StringRes
        val title: Int,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi("", enableUiOn)

    /** 开关 */
    class Switch(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Boolean,
        @StringRes
        val summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi(key, enableUiOn)

    /** 单选（点击弹单选题框） */
    class StringList<T : Any>(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: T,
        val codec: StringLikeCodec<T>,
        val entryValues: List<T>,
        @StringRes
        val entryLabels: List<Int>,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi(key, enableUiOn) {

        /** 已持久化的原始字符串对应第几项（找不到返回 -1） */
        fun indexOfRaw(raw: String?): Int =
            entryValues.indexOfFirst { codec.encode(it) == raw }
    }

    /** 数字输入（范围超出 500 档时使用） */
    class EditTextInt(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi(key, enableUiOn)

    /** 单滑杆 */
    class SeekBarInt(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        val step: Int = 1,
        @StringRes
        val defaultLabel: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi(key, enableUiOn)

    /** 双滑杆（键水平/垂直间距，唯一使用点 [com.whale.imemodule.data.theme.ThemePrefs]） */
    class TwinSeekBarInt(
        @StringRes
        val title: Int,
        @StringRes
        val label: Int,
        key: String,
        val defaultValue: Int,
        @StringRes
        val secondaryLabel: Int,
        val secondaryKey: String,
        val secondaryDefaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        val step: Int = 1,
        @StringRes
        val defaultLabel: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi(key, enableUiOn)
}
