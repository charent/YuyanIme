package com.whale.imemodule.compose.crop

import android.graphics.Rect
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 裁剪屏的变换状态。
 *
 * 变换值存成普通 Compose 状态，由 `graphicsLayer` 的 lambda 内读取 —— 属于 layer
 * 阶段失效，手势过程中不触发重组。手势进行中直接赋值（零延迟跟随手指），
 * 只有离散状态变化（松手 clamp、双击复位、旋转按钮）才走声明式动画。
 *
 * 手势写入之所以不用 `Animatable`：`awaitEachGesture` 的接收者是
 * `@RestrictsSuspension` 的 `AwaitPointerEventScope`，里面只能调用该接收者的挂起函数。
 */
@Stable
internal class CropEditorState(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val initialRect: Rect?,
) {

    /** 取景框尺寸（屏幕像素），由布局给出 */
    var frameWidth by mutableFloatStateOf(0f)
        private set
    var frameHeight by mutableFloatStateOf(0f)
        private set

    /** 旋转步进（×90°），顺时针 */
    var rotationStep by mutableIntStateOf(0)
        private set

    /** 手势进行中（驱动网格线 / 角标的浮现） */
    var gestureActive by mutableStateOf(false)
        private set

    /** 相对「铺满」基准的缩放倍数；在 graphicsLayer 内读取 */
    var scale by mutableFloatStateOf(1f)
        private set

    /** 以取景框中心为原点的平移（屏幕像素）；在 graphicsLayer 内读取 */
    var offset by mutableStateOf(Offset.Zero)
        private set

    private var baseWidth = 1f
    private var baseHeight = 1f
    private var appliedFrameW = 0f
    private var initialized = false

    val rotation: Int get() = rotationStep * 90

    /** 当前变换快照（含旋转角） */
    val transform: CropGeometry.Transform
        get() = CropGeometry.Transform(
            scale = scale,
            offsetX = offset.x,
            offsetY = offset.y,
            rotation = rotation,
        )

    /**
     * 取景框尺寸变化（首帧 / 屏幕旋转）。
     *
     * 首帧按 `initialRect` 反解初始取景；之后屏幕尺寸变化只需按比例换算平移量 ——
     * `scale` 是相对「铺满」的倍数，宽高比固定时 `minScale` 与框尺寸无关。
     */
    fun onFrameChanged(frameW: Float, frameH: Float, baseW: Float, baseH: Float) {
        if (frameW <= 0f || frameH <= 0f || baseW <= 0f || baseH <= 0f) return
        frameWidth = frameW
        frameHeight = frameH
        baseWidth = baseW
        baseHeight = baseH

        if (!initialized) {
            initialized = true
            appliedFrameW = frameW
            val t = CropGeometry.initialTransform(
                frameW = frameW,
                frameH = frameH,
                imageW = imageWidth,
                imageH = imageHeight,
                baseW = baseW,
                baseH = baseH,
                rect = initialRect,
            )
            applyImmediately(t)
            return
        }

        if (appliedFrameW > 0f && appliedFrameW != frameW) {
            val k = frameW / appliedFrameW
            applyImmediately(
                CropGeometry.clamp(
                    frameW = frameW,
                    frameH = frameH,
                    baseW = baseW,
                    baseH = baseH,
                    transform = transform.copy(offsetX = offset.x * k, offsetY = offset.y * k),
                )
            )
        }
        appliedFrameW = frameW
    }

    /**
     * 手势：单指平移、双指缩放。缩放锚点取手势质心 ——
     * 先解出「质心下的图像点保持不动」所需的平移，再落到状态里。
     */
    fun onGesture(centroid: Offset, pan: Offset, zoom: Float, frameCenter: Offset) {
        if (frameWidth <= 0f || frameHeight <= 0f) return
        gestureActive = true

        val minS = CropGeometry.minScale(frameWidth, frameHeight, baseWidth, baseHeight, rotation)
        val current = scale
        val target = (current * zoom).coerceIn(minS, minS * CropGeometry.MAX_ZOOM)
        val k = if (current > 0f) target / current else 1f
        // 质心相对框中心：图像绕质心缩放时，平移需按 (c - o) * k 补偿
        val c = centroid - frameCenter

        scale = target
        offset = c - (c - offset) * k + pan
    }

    /** 手势结束：网格隐去；越界的平移 / 缩放交给 [settle] 用 spring 收敛 */
    fun onGestureEnd() {
        gestureActive = false
    }

    /** 松手后的收敛（触底回弹）：由组合侧在普通协程里调用 */
    suspend fun settle() {
        if (frameWidth <= 0f) return
        animateTo(CropGeometry.clamp(frameWidth, frameHeight, baseWidth, baseHeight, transform))
    }

    /** 双击复位：回到「铺满居中」 */
    suspend fun resetTransform() {
        if (frameWidth <= 0f) return
        animateTo(
            CropGeometry.Transform(
                scale = CropGeometry.minScale(
                    frameWidth, frameHeight, baseWidth, baseHeight, rotation,
                ),
                offsetX = 0f,
                offsetY = 0f,
                rotation = rotation,
            )
        )
    }

    /** 顺时针旋转 90°，并同步把变换收敛到新方向下的合法范围 */
    suspend fun rotate() {
        val next = (rotationStep + 1) % 4
        rotationStep = next
        if (frameWidth <= 0f) return
        animateTo(CropGeometry.clamp(frameWidth, frameHeight, baseWidth, baseHeight, transform))
    }

    /** 当前取景对应的源像素区域（供裁剪执行与下次回填） */
    fun cropRect(): Rect = CropGeometry.sourceRect(
        frameW = frameWidth,
        frameH = frameHeight,
        imageW = imageWidth,
        imageH = imageHeight,
        baseW = baseWidth,
        baseH = baseHeight,
        transform = transform,
    )

    private fun applyImmediately(t: CropGeometry.Transform) {
        scale = t.scale
        offset = Offset(t.offsetX, t.offsetY)
    }

    private suspend fun animateTo(target: CropGeometry.Transform) = coroutineScope {
        launch {
            animate(
                initialValue = scale,
                targetValue = target.scale,
                typeConverter = Float.VectorConverter,
                animationSpec = ScaleSettleSpec,
            ) { value, _ -> scale = value }
        }
        launch {
            val goal = Offset(target.offsetX, target.offsetY)
            animate(
                initialValue = offset,
                targetValue = goal,
                typeConverter = Offset.VectorConverter,
                animationSpec = OffsetSettleSpec,
            ) { value, _ -> offset = value }
        }
    }

    private companion object {
        /** 松手回弹 / 旋转收敛：轻微过冲，300ms 量级自然收敛 */
        val ScaleSettleSpec = spring<Float>(
            dampingRatio = 0.85f,
            stiffness = Spring.StiffnessMediumLow,
        )

        val OffsetSettleSpec = spring<Offset>(
            dampingRatio = 0.85f,
            stiffness = Spring.StiffnessMediumLow,
        )
    }
}
