package com.yuyan.imemodule.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager

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
 */
@Composable
fun ImeTheme(content: @Composable () -> Unit) {
    var theme by remember { mutableStateOf(ThemeManager.activeTheme) }
    DisposableEffect(Unit) {
        val listener = ThemeManager.OnThemeChangeListener { theme = it }
        ThemeManager.addOnChangedListener(listener)
        onDispose { ThemeManager.removeOnChangedListener(listener) }
    }
    CompositionLocalProvider(LocalImeTheme provides theme, content = content)
}
