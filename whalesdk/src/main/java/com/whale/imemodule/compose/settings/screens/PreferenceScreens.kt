package com.whale.imemodule.compose.settings.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whale.imemodule.R
import com.whale.imemodule.application.CustomConstant
import com.whale.imemodule.compose.settings.ManagedPreferences
import com.whale.imemodule.compose.settings.SettingsDest
import com.whale.imemodule.compose.settings.SettingsInfoRow
import com.whale.imemodule.compose.settings.SettingsPage
import com.whale.imemodule.compose.settings.SettingsRow
import com.whale.imemodule.compose.settings.string
import com.whale.imemodule.keyboard.KeyboardManager
import com.whale.imemodule.manager.InputModeSwitcher
import com.whale.imemodule.prefs.AppPrefs
import com.whale.imemodule.prefs.ManagedPreference
import com.whale.imemodule.service.InputDispatcher
import com.whale.imemodule.singleton.EnvironmentSingleton
import com.whale.imemodule.utils.KeyboardLoaderUtil
import com.whale.inputmethod.HWEngine

/** 输入设置（对应旧 `InputSettingsFragment`） */
@Composable
fun InputSettingsScreen(
    onBack: () -> Unit,
    onUserDict: () -> Unit,
) {
    val input = remember { AppPrefs.getInstance().input }

    // 简繁 / 表情直接改 IME option；双拼方案要切输入模式（与旧 Fragment 的监听一致）
    DisposableEffect(Unit) {
        val optionListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
            InputDispatcher.nativeUpdateImeOption()
        }
        val schemaListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
            InputModeSwitcher.switchModeForSetting(
                Pair(
                    InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN,
                    CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + input.doublePYSchemaMode.getValue(),
                )
            )
        }
        input.chineseFanTi.registerOnChangeListener(optionListener)
        input.emojiInput.registerOnChangeListener(optionListener)
        input.doublePYSchemaMode.registerOnChangeListener(schemaListener)
        onDispose {
            input.chineseFanTi.unregisterOnChangeListener(optionListener)
            input.emojiInput.unregisterOnChangeListener(optionListener)
            input.doublePYSchemaMode.unregisterOnChangeListener(schemaListener)
        }
    }

    SettingsPage(title = string(SettingsDest.Input.title), onBack = onBack) {
        ManagedPreferences(providers = listOf(input)) {
            SettingsRow(
                title = string(R.string.user_dict_manage),
                showArrow = true,
                onClick = onUserDict,
            )
        }
    }
}

/** 手写设置（对应旧 `HandwritingSettingsFragment`），16KB 设备上给出不可用说明 */
@Composable
fun HandwritingSettingsScreen(onBack: () -> Unit) {
    val handwriting = remember { AppPrefs.getInstance().handwriting }
    SettingsPage(title = string(SettingsDest.Handwriting.title), onBack = onBack) {
        ManagedPreferences(providers = listOf(handwriting)) {
            if (!HWEngine.isAvailable) {
                SettingsInfoRow(
                    title = string(R.string.handwriting_unavailable_title),
                    summary = string(R.string.handwriting_unavailable_summary),
                )
            }
        }
    }
}

/** 键盘设置（对应旧 `KeyboardSettingFragment`）：离开页面时重算环境尺寸并重载键盘 */
@Composable
fun KeyboardSettingsScreen(onBack: () -> Unit) {
    val keyboardSetting = remember { AppPrefs.getInstance().keyboardSetting }
    DisposableEffect(Unit) {
        onDispose {
            EnvironmentSingleton.instance.initData()
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
        }
    }
    SettingsPage(title = string(SettingsDest.Keyboard.title), onBack = onBack) {
        ManagedPreferences(providers = listOf(keyboardSetting))
    }
}

/** 剪贴板设置（对应旧 `ClipboardSettingsFragment`） */
@Composable
fun ClipboardSettingsScreen(onBack: () -> Unit) {
    val clipboard = remember { AppPrefs.getInstance().clipboard }
    SettingsPage(title = string(SettingsDest.Clipboard.title), onBack = onBack) {
        ManagedPreferences(providers = listOf(clipboard))
    }
}

/** 语音设置（对应旧 `VoiceSettingsFragment`，当前无条目） */
@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
    val voice = remember { AppPrefs.getInstance().voice }
    SettingsPage(title = string(SettingsDest.Voice.title), onBack = onBack) {
        ManagedPreferences(providers = listOf(voice))
        Spacer(Modifier.height(24.dp))
    }
}
