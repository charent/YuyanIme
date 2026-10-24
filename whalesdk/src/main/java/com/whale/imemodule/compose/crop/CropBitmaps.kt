package com.whale.imemodule.compose.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import kotlin.math.max

/**
 * 裁剪屏的位图加载 / 裁剪 / 落盘（全部为阻塞调用，由调用方切到 IO 线程）。
 *
 * EXIF 方向在加载阶段直接烘焙进位图，之后所有矩阵运算都不必再考虑 EXIF。
 */
internal object CropBitmaps {

    /** 工作位图长边上限，对齐旧 `BitmapLoadingWorkerJob` 的采样档位，避免大图 OOM */
    private const val MAX_WORK_DIMENSION = 2048

    /**
     * 采样加载（长边 ≤ [MAX_WORK_DIMENSION]）并按 EXIF 摆正。
     *
     * 返回的位图同时用于交互显示与裁剪取样，因此它的像素坐标系就是
     * [CropResult.cropRect] 的坐标系。
     */
    fun load(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        return applyExifOrientation(context, uri, decoded)
    }

    /**
     * 从工作位图裁出 [rect]，再缩放到 `outW × outH` 的精确尺寸。
     *
     * 输出尺寸等于键盘背景的满铺尺寸，因此调用方无需再缩放（旧实现多一步
     * `createScaledBitmap`，见方案 §2.5）。
     */
    fun crop(source: Bitmap, rect: Rect, outW: Int, outH: Int): Bitmap {
        val x = rect.left.coerceIn(0, source.width - 1)
        val y = rect.top.coerceIn(0, source.height - 1)
        val w = rect.width().coerceIn(1, source.width - x)
        val h = rect.height().coerceIn(1, source.height - y)

        val region = Bitmap.createBitmap(source, x, y, w, h)
        if (region.width == outW && region.height == outH) return region

        val scaled = Bitmap.createScaledBitmap(region, outW, outH, true)
        if (scaled !== region) region.recycle()
        return scaled
    }

    /** 以 PNG 写 [uri]（`file://` 直接写文件，其余走 ContentResolver） */
    fun writePng(context: Context, bitmap: Bitmap, uri: Uri) {
        val stream = if (uri.scheme == "file") {
            val path = uri.path ?: error("missing path in $uri")
            File(path).outputStream()
        } else {
            context.contentResolver.openOutputStream(uri) ?: error("cannot open $uri")
        }
        stream.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 长边不超过 [MAX_WORK_DIMENSION] 的最大 2 的幂采样档位 */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        val longest = max(width, height)
        var sample = 1
        while (longest / sample > MAX_WORK_DIMENSION) sample *= 2
        return sample
    }

    private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val exif = runCatching {
            openStream(context, uri)?.use { ExifInterface(it) }
        }.getOrNull() ?: return bitmap

        val matrix = Matrix()
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        val oriented = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    private fun openStream(context: Context, uri: Uri): InputStream? = runCatching {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).inputStream() }
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }.getOrNull()
}
