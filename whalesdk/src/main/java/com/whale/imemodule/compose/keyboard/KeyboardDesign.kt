package com.whale.imemodule.compose.keyboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.imemodule.data.theme.Theme
import com.whale.imemodule.data.theme.ThemeManager
import kotlin.math.abs
import kotlin.math.max

/**
 * 键盘的设计令牌。
 *
 * 迁移期的键盘是「逐像素复刻旧 View」，颜色只从 `Theme` 里各取各的，缺少派生层：
 * 卡片底、次级文字、描边、投影这些新版界面必需的中间色在 `Theme` 里没有，
 * 结果每个面板各写一套 `copy(alpha = …)`，观感彼此割裂。
 *
 * 这里把「键盘主题色 → 一套语义色」的推导集中到一处，
 * 所有面板（键区 / 候选栏 / 功能宫格 / 符号 / 剪贴板）只消费语义色，
 * 于是换主题时整套界面是一起变的。
 */
@Immutable
data class ImePalette(
    val isDark: Boolean,
    /** 键盘底色 */
    val surface: Color,
    /** 字母键帽底色 */
    val key: Color,
    /** 键帽按下色 */
    val keyPressed: Color,
    /** 功能键（符号 / 123 / 中英）底色 */
    val functionKey: Color,
    /** 主文字色 */
    val onSurface: Color,
    /** 次级文字色（标签、拼音、说明） */
    val muted: Color,
    /** 强调色（回车、选中态） */
    val accent: Color,
    val onAccent: Color,
    /** 面板卡片底（功能宫格、剪贴板行、符号肤色条…） */
    val card: Color,
    /** 卡片按下态 */
    val cardPressed: Color,
    /** 分隔线 / 深色主题下键帽的描边 */
    val outline: Color,
    /** 投影色 */
    val shadow: Color,
)

/** 面板通用圆角：比键帽大一号，形成「卡片 > 键帽」的层级 */
val ImeCardShape = RoundedCornerShape(16.dp)

/** 小控件圆角（图标按钮、候选词胶囊） */
val ImeChipShape = RoundedCornerShape(10.dp)

val LocalImePalette = staticCompositionLocalOf { imePaletteOf(ThemeManager.DefaultTheme) }

/** 从键盘主题推导语义色。传入的 [theme] 可能是任意用户自定义配色，全部要兜底。 */
fun imePaletteOf(theme: Theme): ImePalette {
    val surface = Color(theme.keyboardColor)
    val key = Color(theme.keyBackgroundColor)
    val onSurface = Color(theme.keyTextColor)
    val accent = Color(theme.accentKeyBackgroundColor)
    val rawPress = Color(theme.keyPressHighlightColor)

    // 按下色必须与键帽有可见差异：部分自定义主题把两者设成几乎同色，
    // 此时直接复用会让「按下」完全看不出来，退回「朝文字色靠 12%」。
    val keyPressed = if (channelDistance(key, rawPress) < 0.05f) lerp(key, onSurface, 0.12f) else rawPress
    // 卡片底：浅色主题用键帽色（白卡片浮在灰底上），深色主题把底色提亮一档
    val card = if (theme.isDark) lerp(surface, Color.White, 0.06f) else key
    val cardPressed = if (channelDistance(card, rawPress) < 0.05f) lerp(card, onSurface, 0.10f) else rawPress

    return ImePalette(
        isDark = theme.isDark,
        surface = surface,
        key = key,
        keyPressed = keyPressed,
        functionKey = Color(theme.functionKeyBackgroundColor),
        onSurface = onSurface,
        muted = onSurface.copy(alpha = 0.60f),
        accent = accent,
        onAccent = Color(theme.accentKeyTextColor),
        card = card,
        cardPressed = cardPressed,
        outline = if (theme.isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f),
        shadow = Color.Black,
    )
}

internal fun channelDistance(a: Color, b: Color): Float = max(
    abs(a.red - b.red),
    max(abs(a.green - b.green), abs(a.blue - b.blue)),
)

/**
 * 统一的点击反馈：只保留 ripple「扩散」，**不做缩放**。
 *
 * 早期版本在按下时把控件缩小 6%~14%，在键盘这种密集、快速点击的界面上
 * 会让整块界面的视觉重心连续下沉，用户反馈容易「定位晕眩」。
 * 改成纯 ripple 后，反馈只发生在按下的那一点，局部扩散，不牵动整屏。
 */
@Composable
fun Modifier.imeClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = clickable(enabled = enabled, onClick = onClick)

/** 圆形图标按钮：候选栏入口、面板操作、分页图标统一用这一套 */
@Composable
fun ImeIconButton(
    iconRes: Int,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    iconSize: Dp = 22.dp,
    background: Color = Color.Transparent,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .imeClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * 功能宫格的格子：卡片底 + 图标 + 标签。
 *
 * 不做入场动画（早期版本做过错峰上浮）：格子进场时位置在漂，用户想点的目标
 * 在动，比静态出现更容易点空。按压反馈只有 ripple，不缩放。
 */
@Composable
fun ImeTile(
    iconRes: Int,
    label: String,
    palette: ImePalette,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    labelSize: Int = 11,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(ImeCardShape)
            .background(palette.card.copy(alpha = 0.92f))
            .imeClickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = label,
            colorFilter = ColorFilter.tint(palette.onSurface),
            modifier = Modifier.size(iconSize),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = palette.onSurface.copy(alpha = 0.86f),
            fontSize = labelSize.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 拖动条（悬浮键盘移动条、高度调整把手）：一条圆角胶囊，替代旧的位图线 */
@Composable
fun ImeDragHandle(
    color: Color,
    modifier: Modifier = Modifier,
    width: Dp = 42.dp,
    height: Dp = 4.dp,
) {
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.30f)),
    )
}

/** 键帽圆角：用户偏好是下限，视觉上至少留出键高 18%，避免小圆角带来的「方块感」 */
fun modernKeyRadius(prefRadiusPx: Float, width: Float, height: Float): Float {
    val shortSide = minOf(width, height)
    return max(prefRadiusPx, shortSide * 0.18f).coerceAtMost(shortSide * 0.34f)
}
