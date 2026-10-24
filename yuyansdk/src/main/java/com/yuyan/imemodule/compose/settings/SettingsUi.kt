package com.yuyan.imemodule.compose.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuyan.imemodule.R
import kotlin.math.roundToInt

/**
 * 设置页统一主题。
 *
 * 设置界面跟随系统深色模式（与旧的 DayNight 主题一致），
 * 不用键盘主题——那套颜色是为键盘绘制的，直接用在文字界面上对比度不可控。
 */
@Composable
fun SettingsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

/** 带标题栏的设置页骨架 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    title: String,
    onBack: (() -> Unit)? = null,
    /** 列表型页面自己滚动（LazyColumn）时要关掉这一层 verticalScroll，否则嵌套滚动会冲突 */
    scrollable: Boolean = true,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back_24),
                                contentDescription = null,
                            )
                        }
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            content = content,
        )
    }
}

/** 分组标题（对应旧 PreferenceCategory） */
@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
    )
    Column(modifier = Modifier.fillMaxWidth(), content = content)
    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * 通用设置行：标题 / 摘要 / 右侧控件。
 *
 * 与旧 Preference 的排版保持一致（标题 16sp，摘要 12sp 次要色，最小高度 56dp）。
 */
@Composable
fun SettingsRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    showArrow: Boolean = false,
    iconRes: Int? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null && enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            if (!summary.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        if (showArrow) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "›",
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(
        title = title,
        summary = summary,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

/** 纯信息行（不可点击），用于「16KB 设备手写不可用」这类说明 */
@Composable
fun SettingsInfoRow(title: String, summary: String?) {
    SettingsRow(title = title, summary = summary, enabled = false)
}

/** 单选对话框 */
@Composable
fun SingleChoiceDialog(
    title: String,
    labels: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelected: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                labels.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelected(index)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = {
                            onSelected(index)
                            onDismiss()
                        })
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(string(R.string.cancel)) }
        },
    )
}

/** 数字输入对话框（带 min/max 校验，非法输入时禁用确定） */
@Composable
fun NumberInputDialog(
    title: String,
    initial: Int,
    min: Int,
    max: Int,
    unit: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed in min..max
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '-' } },
                    singleLine = true,
                    isError = !valid,
                    label = { Text("$min ~ $max $unit".trim()) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm); onDismiss() }, enabled = valid) {
                Text(string(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(string(R.string.cancel)) }
        },
    )
}

/** 单滑杆对话框（step 离散；defaultLabel 非空时提供「恢复默认」） */
@Composable
fun SliderDialog(
    title: String,
    initial: Int,
    min: Int,
    max: Int,
    step: Int,
    unit: String,
    defaultLabel: String?,
    defaultValue: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(initial.coerceIn(min, max).toFloat()) }
    val steps = if (step > 1) ((max - min) / step) - 1 else 0
    val current = snap(value, min, max, step)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = if (defaultValue != null && current == defaultValue && defaultLabel != null)
                        defaultLabel else "$current $unit",
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = steps.coerceAtLeast(0),
                )
                if (defaultLabel != null && defaultValue != null) {
                    TextButton(onClick = { value = defaultValue.toFloat() }) {
                        Text(defaultLabel)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current); onDismiss() }) {
                Text(string(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(string(R.string.cancel)) }
        },
    )
}

/** 双滑杆对话框（键水平/垂直间距） */
@Composable
fun TwinSliderDialog(
    title: String,
    primaryLabel: String,
    secondaryLabel: String,
    primary: Int,
    secondary: Int,
    min: Int,
    max: Int,
    step: Int,
    unit: String,
    defaultLabel: String?,
    primaryDefault: Int,
    secondaryDefault: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    var p by remember { mutableStateOf(primary.coerceIn(min, max).toFloat()) }
    var s by remember { mutableStateOf(secondary.coerceIn(min, max).toFloat()) }
    val steps = if (step > 1) ((max - min) / step) - 1 else 0
    val pv = snap(p, min, max, step)
    val sv = snap(s, min, max, step)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("$primaryLabel: $pv $unit", fontSize = 15.sp)
                Slider(
                    value = p,
                    onValueChange = { p = it },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = steps.coerceAtLeast(0),
                )
                Spacer(Modifier.height(12.dp))
                Text("$secondaryLabel: $sv $unit", fontSize = 15.sp)
                Slider(
                    value = s,
                    onValueChange = { s = it },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = steps.coerceAtLeast(0),
                )
                if (defaultLabel != null) {
                    TextButton(onClick = {
                        p = primaryDefault.toFloat()
                        s = secondaryDefault.toFloat()
                    }) { Text(defaultLabel) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pv, sv); onDismiss() }) {
                Text(string(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(string(R.string.cancel)) }
        },
    )
}

/** 把滑杆浮点值吸附到 [min, max] 上以 [step] 为步长的离散点 */
private fun snap(value: Float, min: Int, max: Int, step: Int): Int {
    if (step <= 1) return value.roundToInt().coerceIn(min, max)
    val snapped = min + ((value - min) / step).roundToInt() * step
    return snapped.coerceIn(min, max)
}

@Composable
internal fun string(resId: Int): String = androidx.compose.ui.res.stringResource(resId)

/** 设置页内容区左右留白（卡片式分组之间的间距） */
internal val SettingsContentPadding = PaddingValues(vertical = 8.dp)
