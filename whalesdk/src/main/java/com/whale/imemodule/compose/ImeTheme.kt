package com.whale.imemodule.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.whale.imemodule.compose.keyboard.ImePalette
import com.whale.imemodule.compose.keyboard.LocalImePalette
import com.whale.imemodule.compose.keyboard.imePaletteOf
import com.whale.imemodule.data.theme.Theme
import com.whale.imemodule.data.theme.ThemeManager

/**
 * 当前键盘主题（旧 `Theme` 数据结构，颜色仍是 ARGB Int）。
 *
 * 方案 §2.5：不新建主题体系，而是在 `ThemeManager.activeTheme` 的 `fireChange`
 * 通知链之上包一层 Compose 状态，用 `CompositionLocal` 下发。
 * 颜色在消费处 `Color(theme.xxxColor)` 转换即可。
 */
val LocalImeTheme = staticCompositionLocalOf<Theme> { ThemeManager.DefaultTheme }

/**
 * 订阅 `ThemeManager` 的主题变更。组合常驻期间只注册一次监听，
 * 主题切换时只刷新 [LocalImeTheme]，不会重建组合。
 *
 * 除了键盘主题本身，这里还派生两样东西给上层：
 * - [LocalImePalette]：语义色（卡片底 / 次级文字 / 描边…），各面板共用；
 * - `MaterialTheme`：键盘里的 M3 组件（Slider、Switch、ripple）需要一套配色，
 *   否则会退回 M3 默认的浅色方案，和键盘主题脱节。
 */
@Composable
fun ImeTheme(content: @Composable () -> Unit) {
    var theme by remember { mutableStateOf(ThemeManager.activeTheme) }
    DisposableEffect(Unit) {
        val listener = ThemeManager.OnThemeChangeListener { theme = it }
        ThemeManager.addOnChangedListener(listener)
        onDispose { ThemeManager.removeOnChangedListener(listener) }
    }
    val palette = remember(theme) { imePaletteOf(theme) }
    val colorScheme = remember(palette) { imeColorScheme(palette) }
    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(
            LocalImeTheme provides theme,
            LocalImePalette provides palette,
            content = content,
        )
    }
}

/** 键盘主题 → M3 配色：只映射实际会用到的那几个槽位 */
private fun imeColorScheme(palette: ImePalette) = with(palette) {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    base.copy(
        primary = accent,
        onPrimary = onAccent,
        secondary = accent,
        onSecondary = onAccent,
        background = surface,
        onBackground = onSurface,
        surface = card,
        onSurface = onSurface,
        surfaceVariant = functionKey,
        onSurfaceVariant = muted,
        surfaceContainer = card,
        surfaceContainerHigh = cardPressed,
        outline = onSurface.copy(alpha = 0.35f),
        outlineVariant = outline,
    )
}
