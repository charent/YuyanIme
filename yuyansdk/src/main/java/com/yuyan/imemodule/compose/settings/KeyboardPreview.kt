package com.yuyan.imemodule.compose.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import com.yuyan.imemodule.compose.LocalImeTheme
import com.yuyan.imemodule.compose.keyboard.KeyGrid
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import kotlin.math.roundToInt

/**
 * 键盘实时预览（Compose 版 `KeyboardPreviewView`）。
 *
 * 复用 [KeyGrid] 与同一份 `SoftKeyboard` 数据：预览走的就是真实渲染路径，
 * 因此不需要 `TextKeyboard` 这套旧绘制（Phase 5 已删除）。尺寸按 [scale] 缩放。
 */
@Composable
fun KeyboardPreview(
    theme: Theme,
    modifier: Modifier = Modifier,
    scale: Float = 0.5f,
) {
    val env = remember { EnvironmentSingleton.instance }
    val density = LocalDensity.current
    val widthDp = with(density) { (env.skbWidth * scale).roundToInt().toDp() }
    val heightDp = with(density) { (env.skbHeight * scale).roundToInt().toDp() }

    val background = remember(theme) {
        runCatching {
            theme.backgroundDrawable(ThemeManager.prefs.keyBorder.getValue())
                .toImageBitmap(env.skbWidth, env.skbHeight)
        }.getOrNull()
    }
    val keyboard = remember {
        runCatching {
            val layout = AppPrefs.getInstance().internal.inputDefaultMode.getValue() and
                    InputModeSwitcher.MASK_SKB_LAYOUT
            KeyboardLoaderUtil.instance.getSoftKeyboard(layout)
        }.getOrNull()
    }

    CompositionLocalProvider(LocalImeTheme provides theme) {
        Box(
            modifier = modifier
                .size(widthDp, heightDp)
                .clipToBounds(),
        ) {
            if (background != null) {
                Image(
                    bitmap = background,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (keyboard != null) {
                KeyGrid(keyboard = keyboard, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private fun Drawable.toImageBitmap(width: Int, height: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(
        width.coerceAtLeast(1),
        height.coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}
