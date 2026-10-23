package com.yuyan.imemodule.compose.settings.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.settings.SettingsDest
import com.yuyan.imemodule.compose.settings.SettingsPage
import com.yuyan.imemodule.compose.settings.SettingsRow
import com.yuyan.imemodule.compose.settings.SettingsSection
import com.yuyan.imemodule.compose.settings.string
import com.yuyan.imemodule.ui.setup.SetupActivity
import com.yuyan.imemodule.utils.startActivity

/** 设置首页：输入方式 / 键盘 / 高级 三组入口（对应旧 `ImeSettingsFragment`） */
@Composable
fun HomeScreen(
    onNavigate: (SettingsDest) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    SettingsPage(title = string(SettingsDest.Home.title)) {
        SettingsSection(title = string(R.string.input_methods)) {
            if (SetupActivity.shouldShowUp()) {
                SettingsRow(
                    title = string(R.string.enable_ime),
                    iconRes = R.drawable.ic_menu_language,
                    showArrow = true,
                    onClick = { context.startActivity<SetupActivity>() },
                )
            }
            SettingsRow(
                title = string(R.string.setting_ime_input),
                iconRes = R.drawable.ic_menu_language,
                showArrow = true,
                onClick = { onNavigate(SettingsDest.Input) },
            )
            SettingsRow(
                title = string(R.string.ime_settings_handwriting),
                iconRes = R.drawable.ic_menu_handwriting,
                showArrow = true,
                onClick = { onNavigate(SettingsDest.Handwriting) },
            )
            SettingsRow(
                title = string(R.string.user_dict),
                iconRes = R.drawable.ic_menu_dict,
                showArrow = true,
                onClick = { onNavigate(SettingsDest.UserDict) },
            )
        }

        SettingsSection(title = string(R.string.keyboard)) {
            SettingsRow(
                title = string(R.string.theme),
                iconRes = R.drawable.ic_menu_theme,
                showArrow = true,
                onClick = { onNavigate(SettingsDest.Theme) },
            )
            SettingsRow(
                title = string(R.string.keyboard_feedback),
                iconRes = R.drawable.ic_menu_touch,
                showArrow = true,
                onClick = { onNavigate(SettingsDest.Feedback) },
            )
            SettingsRow(
                title = string(R.string.setting_ime_keyboard),
                iconRes = R.drawable.ic_menu_keyboard,
                showArrow = true,
                onClick = { onNavigate(SettingsDest.Keyboard) },
            )
            SettingsRow(
                title = string(R.string.clipboard),
                iconRes = R.drawable.ic_menu_clipboard,
                showArrow = true,
                onClick = { onNavigate(SettingsDest.Clipboard) },
            )
            SettingsRow(
                title = string(R.string.full_display_keyboard),
                iconRes = R.drawable.ic_menu_keyboard_full,
                showArrow = true,
                onClick = { onNavigate(SettingsDest.FullDisplay) },
            )
        }

        SettingsSection(title = string(R.string.advanced)) {
            SettingsRow(
                title = string(R.string.setting_ime_other),
                iconRes = R.drawable.ic_menu_more_horiz,
                showArrow = true,
                onClick = { onNavigate(SettingsDest.Other) },
            )
            SettingsRow(
                title = string(R.string.about),
                iconRes = R.drawable.ic_menu_feedback,
                showArrow = true,
                onClick = { onNavigate(SettingsDest.About) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
