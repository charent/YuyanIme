package com.whale.imemodule.compose.crop

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * 裁剪屏的坐标几何（纯函数，无 Compose / 无 Android UI 依赖）。
 *
 * ## 坐标模型
 *
 * 「固定取景框 + 变换图像」：取景框恒定居中、宽高比固定（= 键盘背景
 * `skbWidth : inputAreaHeight`），用户平移 / 缩放 / 旋转图像。
 *
 * 图像的屏幕绘制尺寸 = `基准尺寸 × scale`，其中基准尺寸（[baseSize]）取「铺满」
 * 比例（cover），即 `scale = 1` 时图像刚好覆盖取景框。因此：
 *
 * - `scale` 是相对铺满的倍数，与屏幕密度、取景框尺寸无关；
 * - 旋转按 90° 步进，旋转后的轴对齐包围盒为 `(h, w)` 互换或正向尺寸。
 *
 * ## 核心不变量
 *
 * 取景框任意时刻都被图像完全覆盖，因此输出永远是满框像素。
 * 约束是：`|offsetX| ≤ (包围盒宽 - 框宽) / 2`、`|offsetY| ≤ (包围盒高 - 框高) / 2`，
 * 且 `scale ≥ minScale`（见 [minScale]）。
 */
internal object CropGeometry {

    /** 相对「铺满」基准的最大放大倍数 */
    const val MAX_ZOOM = 8f

    /** 一次变换快照。`offset` 单位是屏幕像素，`scale` 是相对铺满的倍数。 */
    data class Transform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
        val rotation: Int,
    )

    /** 取景框尺寸（宽高比固定） */
    fun frameSize(areaW: Float, areaH: Float, aspectW: Int, aspectH: Int): FloatArray {
        if (areaW <= 0f || areaH <= 0f || aspectW <= 0 || aspectH <= 0) {
            return floatArrayOf(0f, 0f)
        }
        val ratio = aspectW.toFloat() / aspectH
        val w = min(areaW, areaH * ratio)
        return floatArrayOf(w, w / ratio)
    }

    /**
     * `scale = 1` 时图像的绘制尺寸：取「铺满」比例，保证图像刚好盖住取景框。
     */
    fun baseSize(frameW: Float, frameH: Float, imageW: Int, imageH: Int): FloatArray {
        if (frameW <= 0f || frameH <= 0f || imageW <= 0 || imageH <= 0) {
            return floatArrayOf(1f, 1f)
        }
        val cover = max(frameW / imageW, frameH / imageH)
        return floatArrayOf(imageW * cover, imageH * cover)
    }

    /** 旋转 [rotation] 度后，轴对齐包围盒的尺寸（输入为未旋转的绘制尺寸） */
    fun rotatedBounds(w: Float, h: Float, rotation: Int): FloatArray {
        return when (normalize(rotation)) {
            90, 270 -> floatArrayOf(h, w)
            else -> floatArrayOf(w, h)
        }
    }

    /** 保证图像完全覆盖取景框所需的最小 `scale` */
    fun minScale(
        frameW: Float,
        frameH: Float,
        baseW: Float,
        baseH: Float,
        rotation: Int,
    ): Float {
        if (baseW <= 0f || baseH <= 0f) return 1f
        val b = rotatedBounds(baseW, baseH, rotation)
        return max(frameW / b[0], frameH / b[1])
    }

    /** 给定 `scale` 时允许的平移幅度（非负） */
    fun maxOffset(
        frameW: Float,
        frameH: Float,
        baseW: Float,
        baseH: Float,
        scale: Float,
        rotation: Int,
    ): FloatArray {
        val b = rotatedBounds(baseW * scale, baseH * scale, rotation)
        return floatArrayOf(
            max(0f, (b[0] - frameW) / 2f),
            max(0f, (b[1] - frameH) / 2f),
        )
    }

    /** 把变换收敛到合法范围（覆盖不变量） */
    fun clamp(
        frameW: Float,
        frameH: Float,
        baseW: Float,
        baseH: Float,
        transform: Transform,
    ): Transform {
        val minS = minScale(frameW, frameH, baseW, baseH, transform.rotation)
        val s = transform.scale.coerceIn(minS, minS * MAX_ZOOM)
        val limit = maxOffset(frameW, frameH, baseW, baseH, s, transform.rotation)
        return transform.copy(
            scale = s,
            offsetX = transform.offsetX.coerceIn(-limit[0], limit[0]),
            offsetY = transform.offsetY.coerceIn(-limit[1], limit[1]),
        )
    }

    /**
     * 重裁剪回填：由上次的裁剪区域（工作位图像素坐标）反解初始变换。
     *
     * 取 `k = min(框宽/矩形宽, 框高/矩形高)`，让矩形尽量占满取景框（宽高比一致时
     * 两个比值相等，取小者避免溢出框外），再把矩形中心对齐框中心。
     * 旋转固定为 0：历史 `cropRect` 均为无旋转产物，见方案 §2.3。
     */
    fun initialTransform(
        frameW: Float,
        frameH: Float,
        imageW: Int,
        imageH: Int,
        baseW: Float,
        baseH: Float,
        rect: Rect?,
    ): Transform {
        val minS = minScale(frameW, frameH, baseW, baseH, 0)
        if (frameW <= 0f || frameH <= 0f) return Transform(minS, 0f, 0f, 0)
        if (rect == null || rect.width() <= 0 || rect.height() <= 0) {
            return Transform(minS, 0f, 0f, 0)
        }
        val fit = fitIntoImage(rect, imageW, imageH)
        val k = min(frameW / fit.width(), frameH / fit.height())
        // 每个源像素在「铺满」基准下的屏幕像素数
        val perSourcePixel = baseW / imageW
        if (perSourcePixel <= 0f) return Transform(minS, 0f, 0f, 0)
        return clamp(
            frameW = frameW,
            frameH = frameH,
            baseW = baseW,
            baseH = baseH,
            transform = Transform(
                scale = k / perSourcePixel,
                offsetX = -((fit.left + fit.right) / 2f - imageW / 2f) * k,
                offsetY = -((fit.top + fit.bottom) / 2f - imageH / 2f) * k,
                rotation = 0,
            ),
        )
    }

    /**
     * 正向映射：取景框四角 → 工作位图像素坐标的轴对齐矩形（逆运算见 [initialTransform]）。
     *
     * 旋转只取 90° 的整数倍，四角的包围盒即精确的裁剪区域，无需额外裁剪逻辑。
     */
    fun sourceRect(
        frameW: Float,
        frameH: Float,
        imageW: Int,
        imageH: Int,
        baseW: Float,
        baseH: Float,
        transform: Transform,
    ): Rect {
        val unit = (baseW / imageW) * transform.scale
        if (unit <= 0f || imageW <= 0 || imageH <= 0) return Rect(0, 0, max(imageW, 1), max(imageH, 1))

        val hw = frameW / 2f
        val hh = frameH / 2f
        val corners = floatArrayOf(-hw, -hh, hw, -hh, hw, hh, -hw, hh)
        val inverseRotation = normalize(-transform.rotation)

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < corners.size) {
            // 去掉平移（屏幕坐标 → 以框中心为原点），再逆旋转，最后按 unit 折回源像素
            val x = corners[i] - transform.offsetX
            val y = corners[i + 1] - transform.offsetY
            val r = rotate(x, y, inverseRotation)
            val ix = r[0] / unit + imageW / 2f
            val iy = r[1] / unit + imageH / 2f
            if (ix < minX) minX = ix
            if (ix > maxX) maxX = ix
            if (iy < minY) minY = iy
            if (iy > maxY) maxY = iy
            i += 2
        }

        val left = floorToInt(minX).coerceIn(0, imageW - 1)
        val top = floorToInt(minY).coerceIn(0, imageH - 1)
        val right = ceilToInt(maxX).coerceIn(left + 1, imageW)
        val bottom = ceilToInt(maxY).coerceIn(top + 1, imageH)
        return Rect(left, top, right, bottom)
    }

    /**
     * 把矩形收敛到图像范围内。
     *
     * 旧版本保存的 `cropRect` 是在旧裁剪库的采样位图上量的，采样档位变化时可能越界
     * （旧库按视图尺寸采样，这里按长边 2048 采样，两者在大图上通常一致）。
     * 越界时按比例缩小并居中，避免初始变换算出框外区域。
     */
    fun fitIntoImage(rect: Rect, imageW: Int, imageH: Int): Rect {
        if (imageW <= 0 || imageH <= 0) return rect
        val inside = rect.left >= 0 && rect.top >= 0 &&
            rect.right <= imageW && rect.bottom <= imageH &&
            rect.width() > 0 && rect.height() > 0
        if (inside) return rect

        val w = max(rect.width(), 1)
        val h = max(rect.height(), 1)
        val shrink = min(1f, min(imageW.toFloat() / w, imageH.toFloat() / h))
        val nw = max(1, (w * shrink).toInt())
        val nh = max(1, (h * shrink).toInt())
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val left = (cx - nw / 2f).toInt().coerceIn(0, imageW - nw)
        val top = (cy - nh / 2f).toInt().coerceIn(0, imageH - nh)
        return Rect(left, top, left + nw, top + nh)
    }

    /** 屏幕坐标点绕原点旋转 [degrees] 度（仅 90° 步进，走整数分支避免浮点误差） */
    private fun rotate(x: Float, y: Float, degrees: Int): FloatArray {
        return when (normalize(degrees)) {
            90 -> floatArrayOf(-y, x)
            180 -> floatArrayOf(-x, -y)
            270 -> floatArrayOf(y, -x)
            else -> floatArrayOf(x, y)
        }
    }

    private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360

    private fun floorToInt(v: Float): Int = kotlin.math.floor(v.toDouble()).toInt()

    private fun ceilToInt(v: Float): Int = kotlin.math.ceil(v.toDouble()).toInt()
}
