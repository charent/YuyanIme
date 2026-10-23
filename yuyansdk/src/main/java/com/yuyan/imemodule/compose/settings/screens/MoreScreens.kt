package com.yuyan.imemodule.compose.settings.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.settings.ManagedPreferences
import com.yuyan.imemodule.compose.settings.SettingsDest
import com.yuyan.imemodule.compose.settings.SettingsPage
import com.yuyan.imemodule.compose.settings.SettingsRow
import com.yuyan.imemodule.compose.settings.SettingsSection
import com.yuyan.imemodule.compose.settings.SettingsSwitchRow
import com.yuyan.imemodule.compose.settings.SingleChoiceDialog
import com.yuyan.imemodule.compose.settings.string
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.manager.UserDataManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.ManagedPreference
import com.yuyan.imemodule.prefs.behavior.FullDisplayCenterMode
import com.yuyan.imemodule.prefs.behavior.FullDisplayKeyMode
import com.yuyan.imemodule.utils.AppUtil
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.utils.importErrorDialog
import com.yuyan.imemodule.utils.queryFileName
import com.yuyan.imemodule.utils.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 全面屏键盘优化（对应旧 `FullDisplayKeyboardFragment`：两张大图选择 + 三个功能位下拉） */
@Composable
fun FullDisplayKeyboardScreen(onBack: () -> Unit) {
    val internal = remember { AppPrefs.getInstance().internal }
    var revision by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val listener = ManagedPreference.OnChangeListener<Any> { _, _ -> revision++ }
        listOf(
            internal.fullDisplayKeyboardEnable,
            internal.fullDisplayKeyModeLeft,
            internal.fullDisplayKeyModeRight,
            internal.fullDisplayCenterMode,
        ).forEach { it.registerOnChangeListener(listener) }
        onDispose {
            listOf(
                internal.fullDisplayKeyboardEnable,
                internal.fullDisplayKeyModeLeft,
                internal.fullDisplayKeyModeRight,
                internal.fullDisplayCenterMode,
            ).forEach { it.unregisterOnChangeListener(listener) }
            KeyboardManager.instance.clearKeyboard()
        }
    }

    // revision 参与读取，保证偏好外改（如导入配置）也能刷新
    val enabled = remember(revision) { internal.fullDisplayKeyboardEnable.getValue() }
    val keyModes = remember { FullDisplayKeyMode.entries }
    val centerModes = remember { FullDisplayCenterMode.entries }
    val keyLabels = stringArray(R.array.FullDisplayKeyMode)
    val centerLabels = stringArray(R.array.FullDisplayCenterKeyMode)

    SettingsPage(title = string(SettingsDest.FullDisplay.title), onBack = onBack) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ModeCard(
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.keyboard_t9_full_display,
                label = string(R.string.keyboard_full_display_advanced_tip),
                selected = enabled,
                onClick = { internal.fullDisplayKeyboardEnable.setValue(true) },
            )
            ModeCard(
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.keyboard_t9_normal,
                label = string(R.string.keyboard_full_display_normal_tip),
                selected = !enabled,
                onClick = { internal.fullDisplayKeyboardEnable.setValue(false) },
            )
        }
        if (enabled) {
            SettingsSection(title = string(R.string.full_display_keyboard)) {
                ChoiceRow(
                    title = string(R.string.keyboard_full_display_key_left),
                    labels = keyLabels,
                    selected = keyModes.indexOf(
                        runCatching { FullDisplayKeyMode.decode(internal.fullDisplayKeyModeLeft.getValue()) }
                            .getOrDefault(FullDisplayKeyMode.SwitchIme)
                    ),
                    onSelected = { internal.fullDisplayKeyModeLeft.setValue(keyModes[it].name) },
                )
                ChoiceRow(
                    title = string(R.string.keyboard_full_display_key_right),
                    labels = keyLabels,
                    selected = keyModes.indexOf(
                        runCatching { FullDisplayKeyMode.decode(internal.fullDisplayKeyModeRight.getValue()) }
                            .getOrDefault(FullDisplayKeyMode.Clipboard)
                    ),
                    onSelected = { internal.fullDisplayKeyModeRight.setValue(keyModes[it].name) },
                )
                ChoiceRow(
                    title = string(R.string.keyboard_full_display_key_center),
                    labels = centerLabels,
                    selected = centerModes.indexOf(
                        runCatching { FullDisplayCenterMode.decode(internal.fullDisplayCenterMode.getValue()) }
                            .getOrDefault(FullDisplayCenterMode.MoveCursor)
                    ),
                    onSelected = { internal.fullDisplayCenterMode.setValue(centerModes[it].name) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 按键反馈：音效与触感强度（对应旧 `KeyboardFeedbacksFragment` 的双滑杆） */
@Composable
fun FeedbackSettingsScreen(onBack: () -> Unit) {
    val internal = remember { AppPrefs.getInstance().internal }
    var revision by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val listener = ManagedPreference.OnChangeListener<Any> { _, _ -> revision++ }
        internal.soundOnKeyPress.registerOnChangeListener(listener)
        internal.vibrationAmplitude.registerOnChangeListener(listener)
        onDispose {
            internal.soundOnKeyPress.unregisterOnChangeListener(listener)
            internal.vibrationAmplitude.unregisterOnChangeListener(listener)
            KeyboardManager.instance.clearKeyboard()
        }
    }

    val sound = remember(revision) { internal.soundOnKeyPress.getValue() }
    val vibration = remember(revision) { internal.vibrationAmplitude.getValue() }
    val soundEnabled = sound < 5 || sound > 10
    val vibrationEnabled = vibration != 1

    SettingsPage(title = string(SettingsDest.Feedback.title), onBack = onBack) {
        SettingsSection(title = string(R.string.button_sound_volume)) {
            SettingsSwitchRow(
                title = string(R.string.button_sound_volume),
                summary = null,
                checked = soundEnabled,
                enabled = true,
                onCheckedChange = { internal.soundOnKeyPress.setValue(if (it) 0 else 10) },
            )
            FeedbackSlider(
                value = sound.coerceIn(0, 40),
                range = 0f..40f,
                steps = 3,
                enabled = soundEnabled,
                onChangeFinished = {
                    internal.soundOnKeyPress.setValue(it)
                    DevicesUtils.tryPlayKeyDown()
                },
            )
        }
        SettingsSection(title = string(R.string.button_vibration_amplitude)) {
            SettingsSwitchRow(
                title = string(R.string.button_vibration_amplitude),
                summary = null,
                checked = vibrationEnabled,
                enabled = true,
                onCheckedChange = { internal.vibrationAmplitude.setValue(if (it) 0 else 1) },
            )
            FeedbackSlider(
                value = vibration.coerceIn(0, 4),
                range = 0f..4f,
                steps = 3,
                enabled = vibrationEnabled,
                onChangeFinished = {
                    internal.vibrationAmplitude.setValue(it)
                    DevicesUtils.tryVibrate()
                },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 其他设置（对应旧 `OtherSettingsFragment`）：隐藏图标 + 用户数据导入导出 */
@Composable
fun OtherSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val other = remember { AppPrefs.getInstance().other }
    val imeHideIcon = remember { other.imeHideIcon }
    var showImportConfirm by remember { mutableStateOf(false) }
    var exportTimestamp = remember { System.currentTimeMillis() }

    DisposableEffect(Unit) {
        val listener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
            val componentName = android.content.ComponentName(
                context.packageName,
                com.yuyan.imemodule.ui.activity.LauncherActivity::class.java.name,
            )
            context.packageManager.setComponentEnabledSetting(
                componentName,
                if (value) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }
        imeHideIcon.registerOnChangeListener(listener)
        onDispose { imeHideIcon.unregisterOnChangeListener(listener) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) { context.contentResolver.queryFileName(uri) }
            if (name == null || !name.endsWith(".zip")) {
                context.importErrorDialog(R.string.exception_user_data_filename, name ?: "")
                return@launch
            }
            val ok = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use {
                        UserDataManager.import(it).getOrThrow()
                    }
                }.isSuccess
            }
            if (ok) {
                AppUtil.showRestartNotification(context)
                Toast.makeText(context, R.string.user_data_imported, Toast.LENGTH_SHORT).show()
                delay(400L)
                AppUtil.exit()
            } else {
                context.importErrorDialog(IllegalStateException(context.getString(R.string.user_data_imported)))
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use {
                        UserDataManager.export(it).getOrThrow()
                    }
                }
            }
        }
    }

    SettingsPage(title = string(SettingsDest.Other.title), onBack = onBack) {
        ManagedPreferences(providers = listOf(other)) {
            SettingsRow(
                title = string(R.string.export_user_data),
                showArrow = true,
                onClick = {
                    exportTimestamp = System.currentTimeMillis()
                    exportLauncher.launch(
                        "yuyanIme_${TimeUtils.iso8601UTCDateTime(exportTimestamp)}.zip"
                    )
                },
            )
            SettingsRow(
                title = string(R.string.import_user_data),
                showArrow = true,
                onClick = { showImportConfirm = true },
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text(string(R.string.import_user_data)) },
            text = { Text(string(R.string.confirm_import_user_data)) },
            confirmButton = {
                TextButton(onClick = {
                    showImportConfirm = false
                    importLauncher.launch("application/zip")
                }) { Text(string(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) {
                    Text(string(R.string.cancel))
                }
            },
        )
    }
}

// ---------------------------------------------------------------- 局部组件

@Composable
private fun ModeCard(
    modifier: Modifier = Modifier,
    iconRes: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, border, RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent
            )
            .clickable { onClick() }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    labels: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    SettingsRow(
        title = title,
        summary = labels.getOrNull(selected),
        enabled = true,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        SingleChoiceDialog(
            title = title,
            labels = labels,
            selectedIndex = selected,
            onDismiss = { showDialog = false },
            onSelected = onSelected,
        )
    }
}

@Composable
private fun FeedbackSlider(
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onChangeFinished: (Int) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Box(modifier = Modifier.padding(horizontal = 24.dp)) {
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChangeFinished(local.toInt()) },
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

@Composable
private fun stringArray(resId: Int): List<String> {
    val context = LocalContext.current
    return remember(resId) { context.resources.getStringArray(resId).toList() }
}


