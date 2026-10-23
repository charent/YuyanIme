package com.yuyan.imemodule.compose.settings.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.settings.KeyboardPreview
import com.yuyan.imemodule.compose.settings.ManagedPreferences
import com.yuyan.imemodule.compose.settings.SettingsDest
import com.yuyan.imemodule.compose.settings.SettingsPage
import com.yuyan.imemodule.compose.settings.SettingsRow
import com.yuyan.imemodule.compose.settings.SingleChoiceDialog
import com.yuyan.imemodule.compose.settings.string
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.ManagedPreference
import com.yuyan.imemodule.prefs.behavior.SkbStyleMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.ui.theme.CustomThemeActivity
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import java.util.UUID

/**
 * 主题页（对应旧 `ThemeFragment`：预览 + 「主题 / 更多」两个 Tab）。
 *
 * 两个 Tab 分别承载旧 `ThemeSettingsFragment`（主题偏好）与 `ThemeListFragment`（主题列表），
 * ViewPager2 + FragmentStateAdapter 换成 `TabRow` + 条件渲染（方案 §2.4）。
 */
@Composable
fun ThemeScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(ThemeManager.activeTheme) }
    var themes by remember { mutableStateOf(ThemeManager.getAllThemes()) }
    var pendingSelect by remember { mutableStateOf<Theme?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showDuplicate by remember { mutableStateOf(false) }

    val editLauncher = rememberLauncherForActivityResult(CustomThemeActivity.Contract()) { result ->
        when (result) {
            is CustomThemeActivity.BackgroundResult.Created -> {
                ThemeManager.saveTheme(result.theme)
                if (!ThemeManager.prefs.followSystemDayNightTheme.getValue()) {
                    ThemeManager.setNormalModeTheme(result.theme)
                }
            }

            is CustomThemeActivity.BackgroundResult.Updated -> ThemeManager.saveTheme(result.theme)
            is CustomThemeActivity.BackgroundResult.Deleted -> ThemeManager.deleteTheme(result.name)
            null -> Unit
        }
        themes = ThemeManager.getAllThemes()
    }

    // 主题变化：预览、主题列表、键盘缓存（尺寸/键位）都要跟着刷新，与旧 ThemeFragment 一致
    DisposableEffect(Unit) {
        ThemeManager.refreshThemes()
        active = ThemeManager.activeTheme
        themes = ThemeManager.getAllThemes()
        val listener = ThemeManager.OnThemeChangeListener { theme ->
            active = theme
            EnvironmentSingleton.instance.initData()
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
        }
        ThemeManager.addOnChangedListener(listener)
        onDispose { ThemeManager.removeOnChangedListener(listener) }
    }

    SettingsPage(title = string(SettingsDest.Theme.title), onBack = onBack) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            KeyboardPreview(theme = active)
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(string(R.string.theme)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(string(R.string.more)) })
        }

        when (tab) {
            0 -> ThemePreferences()
            else -> {
                SettingsRow(
                    title = string(R.string.new_theme),
                    showArrow = true,
                    onClick = { showAddMenu = true },
                )
                themes.forEach { theme ->
                    val isActive = theme.name == active.name
                    val isLight = ThemeManager.prefs.lightModeTheme.getValue().name == theme.name
                    val isDark = ThemeManager.prefs.darkModeTheme.getValue().name == theme.name
                    SettingsRow(
                        title = theme.name,
                        summary = when {
                            theme is Theme.Custom -> string(R.string.custom_theme)
                            else -> null
                        },
                        trailing = {
                            when {
                                isActive -> Text(
                                    text = "✓",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 18.sp,
                                )

                                isLight -> Text(
                                    text = string(R.string.light_mode_theme),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                isDark -> Text(
                                    text = string(R.string.dark_mode_theme),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (theme is Theme.Custom) {
                                TextButton(onClick = { editLauncher.launch(theme) }) {
                                    Text(string(R.string.edit))
                                }
                            }
                        },
                        onClick = {
                            if (ThemeManager.prefs.followSystemDayNightTheme.getValue()) {
                                pendingSelect = theme
                            } else {
                                ThemeManager.setNormalModeTheme(theme)
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showAddMenu) {
        AlertDialog(
            onDismissRequest = { showAddMenu = false },
            title = { Text(string(R.string.new_theme)) },
            text = {
                Column {
                    TextButton(onClick = {
                        showAddMenu = false
                        editLauncher.launch(null)
                    }) { Text(string(R.string.choose_image)) }
                    TextButton(onClick = {
                        showAddMenu = false
                        showDuplicate = true
                    }) { Text(string(R.string.duplicate_builtin_theme)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddMenu = false }) { Text(string(R.string.cancel)) }
            },
        )
    }

    if (showDuplicate) {
        val builtin = ThemeManager.BuiltinThemes
        SingleChoiceDialog(
            title = string(R.string.duplicate_builtin_theme),
            labels = builtin.map { it.name },
            selectedIndex = -1,
            onDismiss = { showDuplicate = false },
            onSelected = { index ->
                builtin.getOrNull(index)?.let { theme ->
                    val newTheme = theme.deriveCustomNoBackground(UUID.randomUUID().toString())
                    ThemeManager.saveTheme(newTheme)
                    themes = ThemeManager.getAllThemes()
                }
            },
        )
    }

    pendingSelect?.let { theme ->
        AlertDialog(
            onDismissRequest = { pendingSelect = null },
            title = { Text(string(R.string.configure)) },
            text = { Text(string(R.string.theme_message_follow_system_day_night_mode_enabled)) },
            confirmButton = {
                TextButton(onClick = {
                    ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
                    ThemeManager.setNormalModeTheme(theme)
                    pendingSelect = null
                }) { Text(string(R.string.disable_it)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingSelect = null }) { Text(string(R.string.cancel)) }
            },
        )
    }

}

/** 主题偏好（旧 `ThemeSettingsFragment`）：`skbStyleMode` 切换时联动英文数字行 */
@Composable
private fun ThemePreferences() {
    val prefs = remember { ThemeManager.prefs }
    DisposableEffect(Unit) {
        val listener = ManagedPreference.OnChangeListener<SkbStyleMode> { _, value ->
            AppPrefs.getInstance().keyboardSetting.abcNumberLine.setValue(value == SkbStyleMode.Samsung)
            EnvironmentSingleton.instance.initData()
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
        }
        prefs.skbStyleMode.registerOnChangeListener(listener)
        onDispose { prefs.skbStyleMode.unregisterOnChangeListener(listener) }
    }
    ManagedPreferences(providers = listOf(prefs))
}
