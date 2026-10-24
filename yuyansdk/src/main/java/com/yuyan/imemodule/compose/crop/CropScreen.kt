package com.yuyan.imemodule.compose.crop

import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuyan.imemodule.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 裁剪屏。
 *
 * 交互模型是「固定取景框 + 变换图像」：框恒定居中、宽高比固定，用户平移 / 缩放 /
 * 旋转图像，框永远被图像完全覆盖，所以输出永远是满框像素（几何见 [CropGeometry]）。
 *
 * 设计基调（方案 §2.0）：可用优先、沉浸式深色底、动效只表达状态（网格浮现、
 * 松手回弹、旋转过渡），手势即 UI —— 按钮只有取消、旋转、确认。
 */
@Composable
internal fun CropScreen(
    image: ImageBitmap,
    request: CropRequest,
    processing: Boolean,
    onCancel: () -> Unit,
    onConfirm: (Rect) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val state = remember(image) {
        CropEditorState(image.width, image.height, request.initialRect)
    }
    // 旋转走声明式动画：只有点击按钮（离散状态变化）才触发
    val rotation by animateFloatAsState(
        targetValue = state.rotation.toFloat(),
        animationSpec = tween(durationMillis = ROTATION_MILLIS),
        label = "cropRotation",
    )

    Box(modifier = Modifier.fillMaxSize().background(CropBackgroundColor)) {
        Column(modifier = Modifier.fillMaxSize()) {
            CropTopBar(
                enabled = !processing,
                onCancel = onCancel,
                onRotate = { scope.launch { state.rotate() } },
            )

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = FrameHorizontalPadding),
            ) {
                val areaW = constraints.maxWidth.toFloat()
                val areaH = constraints.maxHeight.toFloat()
                val frame = CropGeometry.frameSize(areaW, areaH, request.aspectW, request.aspectH)
                val base = CropGeometry.baseSize(frame[0], frame[1], image.width, image.height)

                LaunchedEffect(frame[0], frame[1]) {
                    state.onFrameChanged(frame[0], frame[1], base[0], base[1])
                }

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(
                                width = with(density) { frame[0].toDp() },
                                height = with(density) { frame[1].toDp() },
                            )
                            .clipToBounds()
                            .cropGestures(
                                key = image,
                                frameW = frame[0],
                                frameH = frame[1],
                                enabled = !processing,
                                state = state,
                                animateScope = scope,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier
                                .size(
                                    width = with(density) { base[0].toDp() },
                                    height = with(density) { base[1].toDp() },
                                )
                                .graphicsLayer {
                                    // 变换值在 lambda 内读取：只在 layer 阶段失效，手势中不重组
                                    scaleX = state.scale
                                    scaleY = state.scale
                                    rotationZ = rotation
                                    translationX = state.offset.x
                                    translationY = state.offset.y
                                },
                        )
                    }

                    CropOverlay(state = state, frameW = frame[0], frameH = frame[1])
                }
            }

            CropBottomBar(
                enabled = !processing,
                onConfirm = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirm(state.cropRect())
                },
            )
        }

        AnimatedVisibility(
            visible = processing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            CropProcessingOverlay()
        }
    }
}

/**
 * 手势：单指平移、双指缩放（锚点 = 质心）、双击复位。
 *
 * 用 [awaitEachGesture] 自建循环而不是 `detectTransformGestures`，因为后者不会
 * 在单次手势结束时返回，拿不到「松手」时机做 clamp + spring 回弹。
 * 该循环的接收者是 `@RestrictsSuspension`，所以回弹动画交给 [animateScope] 起协程。
 */
private fun Modifier.cropGestures(
    key: Any,
    frameW: Float,
    frameH: Float,
    enabled: Boolean,
    state: CropEditorState,
    animateScope: CoroutineScope,
): Modifier = pointerInput(key, frameW, frameH, enabled) {
    if (!enabled) return@pointerInput
    val frameCenter = Offset(frameW / 2f, frameH / 2f)
    var lastTapTime = 0L
    var lastTapPosition = Offset.Zero

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downTime = down.uptimeMillis
        val downPosition = down.position
        var transformed = false

        while (true) {
            val event = awaitPointerEvent()
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            if (zoom != 1f || pan != Offset.Zero) {
                transformed = true
                val centroid = event.calculateCentroid(useCurrent = true)
                state.onGesture(
                    centroid = if (centroid == Offset.Unspecified) frameCenter else centroid,
                    pan = pan,
                    zoom = zoom,
                    frameCenter = frameCenter,
                )
            }
            // 消费位移，避免与上层滚动 / 系统手势争抢
            event.changes.forEach { if (it.positionChanged()) it.consume() }
            if (event.changes.none { it.pressed }) break
        }

        // 松手：网格隐去 + 越界的平移 / 缩放用 spring 收敛（触底回弹）
        state.onGestureEnd()
        animateScope.launch { state.settle() }

        if (!transformed) {
            val elapsed = downTime - lastTapTime
            val doubleTap = lastTapTime > 0L &&
                elapsed in 0..viewConfiguration.doubleTapTimeoutMillis &&
                (downPosition - lastTapPosition).getDistance() <= viewConfiguration.touchSlop * 2f
            if (doubleTap) {
                lastTapTime = 0L
                animateScope.launch { state.resetTransform() }
            } else {
                lastTapTime = downTime
                lastTapPosition = downPosition
            }
        }
    }
}

/**
 * 取景框的绘制层：框外暗角 + 圆角细描边常显；三等分网格与四角角标只在手势期间浮现。
 */
@Composable
private fun CropOverlay(state: CropEditorState, frameW: Float, frameH: Float) {
    val guidesAlpha by animateFloatAsState(
        targetValue = if (state.gestureActive) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "cropGuides",
    )
    val guideColor = Color.White.copy(alpha = 0.9f * guidesAlpha)
    val gridColor = Color.White.copy(alpha = 0.35f * guidesAlpha)

    Canvas(modifier = Modifier.fillMaxSize()) {
        if (frameW <= 0f || frameH <= 0f) return@Canvas
        val left = (size.width - frameW) / 2f
        val top = (size.height - frameH) / 2f
        val frame = ComposeRect(left, top, left + frameW, top + frameH)
        val corner = CornerRadius(12.dp.toPx())

        // 框外暗角：整屏减去圆角取景框（EvenOdd 挖空）
        val scrimPath = Path().apply {
            addRect(ComposeRect(0f, 0f, size.width, size.height))
            addRoundRect(RoundRect(frame, corner))
            fillType = PathFillType.EvenOdd
        }
        drawPath(scrimPath, ScrimColor)

        drawRoundRect(
            color = BorderColor,
            topLeft = frame.topLeft,
            size = Size(frame.width, frame.height),
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx()),
        )

        if (guidesAlpha > 0.01f) {
            val strokeWidth = 1.dp.toPx()
            for (i in 1..2) {
                val x = frame.left + frame.width * i / 3f
                drawLine(gridColor, Offset(x, frame.top), Offset(x, frame.bottom), strokeWidth)
                val y = frame.top + frame.height * i / 3f
                drawLine(gridColor, Offset(frame.left, y), Offset(frame.right, y), strokeWidth)
            }

            // 四角角标：比网格更亮，交代「这里是取景边界」
            val armLength = 20.dp.toPx()
            val armWidth = 2.5.dp.toPx()
            fun cornerLines(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(
                    guideColor,
                    Offset(x, y),
                    Offset(x + armLength * dx, y),
                    armWidth,
                    StrokeCap.Round,
                )
                drawLine(
                    guideColor,
                    Offset(x, y),
                    Offset(x, y + armLength * dy),
                    armWidth,
                    StrokeCap.Round,
                )
            }
            cornerLines(frame.left, frame.top, 1f, 1f)
            cornerLines(frame.right, frame.top, -1f, 1f)
            cornerLines(frame.left, frame.bottom, 1f, -1f)
            cornerLines(frame.right, frame.bottom, -1f, -1f)
        }
    }
}

@Composable
private fun CropTopBar(enabled: Boolean, onCancel: () -> Unit, onRotate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel, enabled = enabled) {
            Icon(
                painter = painterResource(R.drawable.ic_menu_close),
                contentDescription = stringResource(R.string.cancel),
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            // 与确认按钮同一文案：本屏只有「裁剪」一个动作，标题无需另造字符串
            text = stringResource(R.string.crop_confirm),
            modifier = Modifier.weight(1f),
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onRotate, enabled = enabled) {
            Icon(
                painter = painterResource(R.drawable.ic_rotate_right_24),
                contentDescription = stringResource(R.string.crop_rotate),
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun CropBottomBar(enabled: Boolean, onConfirm: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 确认落在拇指热区
        FilledTonalButton(
            onClick = onConfirm,
            enabled = enabled,
            modifier = Modifier
                .widthIn(min = 168.dp)
                .heightIn(min = 48.dp),
        ) {
            Text(text = stringResource(R.string.crop_confirm), fontSize = 16.sp)
        }
    }
}

/** 解码期间的占位：深色底 + 居中进度 + 可退出 */
@Composable
internal fun CropLoadingScreen(onCancel: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(CropBackgroundColor)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = Color.White)
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier.statusBarsPadding().padding(4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_menu_close),
                contentDescription = stringResource(R.string.cancel),
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** 处理中遮罩：淡入覆盖并拦截手势，避免重复确认 */
@Composable
private fun CropProcessingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Color.White)
    }
}

private val CropBackgroundColor = Color.Black
private val ScrimColor = Color.Black.copy(alpha = 0.55f)
private val BorderColor = Color.White.copy(alpha = 0.85f)
private val FrameHorizontalPadding = 20.dp
private const val ROTATION_MILLIS = 220
