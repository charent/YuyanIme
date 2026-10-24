package com.whale.imemodule.compose.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.imemodule.data.theme.ManagedThemePreference
import com.whale.imemodule.data.theme.ManagedThemePreferenceUi
import com.whale.imemodule.data.theme.ThemeManager
import com.whale.imemodule.prefs.ManagedPreference
import com.whale.imemodule.prefs.ManagedPreferenceProvider
import com.whale.imemodule.prefs.ManagedPreferenceUi

/**
 * 元数据驱动的设置渲染器（方案 §2.4）。
 *
 * 输入是 [ManagedPreferenceProvider]（数据 + UI 元数据），输出是 Compose 控件：
 *
 * | 元数据 | 控件 |
 * |---|---|
 * | [ManagedPreferenceUi.Category] | 分组标题 |
 * | [ManagedPreferenceUi.Switch] | 行 + Switch |
 * | [ManagedPreferenceUi.StringList] | 行 + 单选对话框 |
 * | [ManagedPreferenceUi.EditTextInt] | 行 + 数字输入对话框 |
 * | [ManagedPreferenceUi.SeekBarInt] | 行 + 滑杆对话框 |
 * | [ManagedPreferenceUi.TwinSeekBarInt] | 行 + 双滑杆对话框 |
 * | [ManagedThemePreferenceUi] | 行 + 主题选择对话框 |
 *
 * 可编辑性由元数据里的 `enableUiOn` 决定：任意偏好变化后重新求值，
 * 等价于旧 `ManagedPreferenceVisibilityEvaluator`（方案要求「机制保留、回调换状态」）。
 */
@Composable
fun ManagedPreferences(
    providers: List<ManagedPreferenceProvider>,
    modifier: Modifier = Modifier,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val revision = rememberPreferencesRevision(providers)
    val enabled = remember(revision) {
        providers.flatMap { it.managedPreferencesUi }.associate { it.key to it.isEnabled() }
    }
    val groups = remember(providers) { buildGroups(providers) }

    Column(modifier = modifier) {
        groups.forEach { group ->
            group.title?.let { titleRes ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = string(titleRes),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            group.items.forEach { item ->
                PreferenceRow(
                    item = item,
                    enabled = enabled[item.key] ?: true,
                    revision = revision,
                    providers = providers,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        footer()
    }
}

/**
 * 订阅一组 provider 的所有偏好，返回随任意偏好变化自增的版本号。
 *
 * 设置页读取值的唯一失效源：`pref.getValue()` 本身不带观察能力，
 * 靠这个版本号驱动重组，避免每个控件各自注册监听。
 */
@Composable
fun rememberPreferencesRevision(providers: List<ManagedPreferenceProvider>): Int {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(providers) {
        val listener = ManagedPreference.OnChangeListener<Any> { _, _ -> revision++ }
        providers.forEach { provider ->
            provider.managedPreferences.values.forEach { it.registerOnChangeListener(listener) }
        }
        onDispose {
            providers.forEach { provider ->
                provider.managedPreferences.values.forEach { it.unregisterOnChangeListener(listener) }
            }
        }
    }
    return revision
}

private class UiGroup(val title: Int?, val items: MutableList<ManagedPreferenceUi> = mutableListOf())

private fun buildGroups(providers: List<ManagedPreferenceProvider>): List<UiGroup> {
    val groups = mutableListOf<UiGroup>()
    var current = UiGroup(null)
    providers.forEach { provider ->
        provider.managedPreferencesUi.forEach { ui ->
            if (ui is ManagedPreferenceUi.Category) {
                if (current.items.isNotEmpty()) groups += current
                current = UiGroup(ui.title)
            } else {
                current.items += ui
            }
        }
    }
    if (current.items.isNotEmpty()) groups += current
    return groups
}

@Composable
private fun PreferenceRow(
    item: ManagedPreferenceUi,
    enabled: Boolean,
    revision: Int,
    providers: List<ManagedPreferenceProvider>,
) {
    when (item) {
        is ManagedPreferenceUi.Switch -> {
            val pref = providers.pBool(item.key)
            val checked = remember(item.key, revision) { pref?.getValue() ?: item.defaultValue }
            SettingsSwitchRow(
                title = string(item.title),
                summary = item.summary?.let { string(it) },
                checked = checked,
                enabled = enabled,
                onCheckedChange = { pref?.setValue(it) },
            )
        }

        is ManagedPreferenceUi.StringList<*> -> {
            val pref = providers.pStringLike(item.key)
            val raw = remember(item.key, revision) { pref?.getValue()?.toString() }
            val labels = item.entryLabels.map { string(it) }
            val selected = remember(raw) { item.indexOfRaw(raw) }
            var showDialog by remember { mutableStateOf(false) }
            SettingsRow(
                title = string(item.title),
                summary = labels.getOrNull(selected),
                enabled = enabled,
                onClick = { showDialog = true },
            )
            if (showDialog) {
                SingleChoiceDialog(
                    title = string(item.title),
                    labels = labels,
                    selectedIndex = selected,
                    onDismiss = { showDialog = false },
                    onSelected = { index ->
                        item.entryValues.getOrNull(index)?.let { value ->
                            @Suppress("UNCHECKED_CAST")
                            (pref as? ManagedPreference<Any>)?.setValue(value)
                        }
                    },
                )
            }
        }

        is ManagedPreferenceUi.EditTextInt -> {
            val pref = providers.pInt(item.key)
            val value = remember(item.key, revision) { pref?.getValue() ?: item.defaultValue }
            var showDialog by remember { mutableStateOf(false) }
            SettingsRow(
                title = string(item.title),
                summary = "$value ${item.unit}".trim(),
                enabled = enabled,
                onClick = { showDialog = true },
            )
            if (showDialog) {
                NumberInputDialog(
                    title = string(item.title),
                    initial = value,
                    min = item.min,
                    max = item.max,
                    unit = item.unit,
                    onDismiss = { showDialog = false },
                    onConfirm = { pref?.setValue(it) },
                )
            }
        }

        is ManagedPreferenceUi.SeekBarInt -> {
            val pref = providers.pInt(item.key)
            val value = remember(item.key, revision) { pref?.getValue() ?: item.defaultValue }
            val defaultLabel = item.defaultLabel?.let { string(it) }
            var showDialog by remember { mutableStateOf(false) }
            SettingsRow(
                title = string(item.title),
                summary = if (value == item.defaultValue && defaultLabel != null) defaultLabel
                else "$value ${item.unit}".trim(),
                enabled = enabled,
                onClick = { showDialog = true },
            )
            if (showDialog) {
                SliderDialog(
                    title = string(item.title),
                    initial = value,
                    min = item.min,
                    max = item.max,
                    step = item.step,
                    unit = item.unit,
                    defaultLabel = defaultLabel,
                    defaultValue = item.defaultValue,
                    onDismiss = { showDialog = false },
                    onConfirm = { pref?.setValue(it) },
                )
            }
        }

        is ManagedPreferenceUi.TwinSeekBarInt -> {
            val primary = providers.pInt(item.key)
            val secondary = providers.pInt(item.secondaryKey)
            val pValue = remember(item.key, revision) { primary?.getValue() ?: item.defaultValue }
            val sValue = remember(item.secondaryKey, revision) {
                secondary?.getValue() ?: item.secondaryDefaultValue
            }
            val defaultLabel = item.defaultLabel?.let { string(it) }
            var showDialog by remember { mutableStateOf(false) }
            SettingsRow(
                title = string(item.title),
                summary = "$pValue / $sValue ${item.unit}".trim(),
                enabled = enabled,
                onClick = { showDialog = true },
            )
            if (showDialog) {
                TwinSliderDialog(
                    title = string(item.title),
                    primaryLabel = string(item.label),
                    secondaryLabel = string(item.secondaryLabel),
                    primary = pValue,
                    secondary = sValue,
                    min = item.min,
                    max = item.max,
                    step = item.step,
                    unit = item.unit,
                    defaultLabel = defaultLabel,
                    primaryDefault = item.defaultValue,
                    secondaryDefault = item.secondaryDefaultValue,
                    onDismiss = { showDialog = false },
                    onConfirm = { p, s ->
                        primary?.setValue(p)
                        secondary?.setValue(s)
                    },
                )
            }
        }

        is ManagedThemePreferenceUi -> {
            val pref = providers.theme(item.key)
            val current = remember(item.key, revision) { pref?.getValue() ?: item.defaultValue }
            var showDialog by remember { mutableStateOf(false) }
            SettingsRow(
                title = string(item.title),
                summary = (item.summary?.let { string(it) } ?: current.name),
                enabled = enabled,
                onClick = { showDialog = true },
            )
            if (showDialog) {
                val themes = ThemeManager.getAllThemes()
                SingleChoiceDialog(
                    title = string(item.title),
                    labels = themes.map { it.name },
                    selectedIndex = themes.indexOfFirst { it.name == current.name },
                    onDismiss = { showDialog = false },
                    onSelected = { index ->
                        themes.getOrNull(index)?.let { pref?.setValue(it) }
                    },
                )
            }
        }

        else -> Unit
    }
}

private fun List<ManagedPreferenceProvider>.findPref(key: String): ManagedPreference<*>? {
    forEach { provider ->
        provider.managedPreferences[key]?.let { return it }
    }
    return null
}

private fun List<ManagedPreferenceProvider>.pBool(key: String): ManagedPreference.PBool? =
    findPref(key) as? ManagedPreference.PBool

private fun List<ManagedPreferenceProvider>.pInt(key: String): ManagedPreference.PInt? =
    findPref(key) as? ManagedPreference.PInt

private fun List<ManagedPreferenceProvider>.pStringLike(key: String): ManagedPreference.PStringLike<*>? =
    findPref(key) as? ManagedPreference.PStringLike<*>

private fun List<ManagedPreferenceProvider>.theme(key: String): ManagedThemePreference? =
    findPref(key) as? ManagedThemePreference
