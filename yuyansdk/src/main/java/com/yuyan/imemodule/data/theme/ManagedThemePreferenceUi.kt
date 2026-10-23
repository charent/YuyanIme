package com.yuyan.imemodule.data.theme

import androidx.annotation.StringRes
import com.yuyan.imemodule.prefs.ManagedPreferenceUi

/**
 * 主题选择项的 UI 元数据（浅色主题 / 深色主题两行）。
 *
 * Phase 4 起不再创建 `ThemeSelectPreference`，由 Compose 设置渲染器弹主题列表。
 */
class ManagedThemePreferenceUi(
    @StringRes
    val title: Int,
    key: String,
    val defaultValue: Theme,
    @StringRes
    val summary: Int? = null,
    enableUiOn: (() -> Boolean)? = null
) : ManagedPreferenceUi(key, enableUiOn)
