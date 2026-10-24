package com.whale.imemodule.compose.crop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * 裁剪请求。
 *
 * @param sourceUri 源图（本工程内恒为 `file://`，见 `CustomThemeViewModel.srcFile`）
 * @param aspectW 固定宽高比的分子，= `EnvironmentSingleton.skbWidth`
 * @param aspectH 固定宽高比的分母，= `EnvironmentSingleton.inputAreaHeight`
 * @param initialRect 重裁剪回填：上次的裁剪区域，工作位图像素坐标
 * @param outputUri PNG 输出目标（`CustomThemeViewModel.prepareTempFile` 的缓存文件）
 */
data class CropRequest(
    val sourceUri: Uri,
    val aspectW: Int,
    val aspectH: Int,
    val initialRect: Rect? = null,
    val outputUri: Uri,
)

/**
 * 裁剪结果。
 *
 * @param outputUri 已写好的 PNG（尺寸恰为 `aspectW × aspectH`，调用方无需再缩放）
 * @param cropRect 本次裁剪区域（工作位图像素坐标），供下次重裁剪回填
 * @param originalUri 源图 uri，供调用方取扩展名 / 备份原始字节
 */
data class CropResult(
    val outputUri: Uri,
    val cropRect: Rect,
    val originalUri: Uri,
)

/**
 * 裁剪屏的 [ActivityResultContract]。
 *
 * 取消（含解码失败）统一返回 `null`，调用方据此走「放弃本次编辑」分支。
 */
class CropContract : ActivityResultContract<CropRequest, CropResult?>() {

    override fun createIntent(context: Context, input: CropRequest): Intent =
        Intent(context, CropActivity::class.java).apply {
            putExtra(EXTRA_SOURCE, input.sourceUri)
            putExtra(EXTRA_ASPECT_W, input.aspectW)
            putExtra(EXTRA_ASPECT_H, input.aspectH)
            putExtra(EXTRA_OUTPUT, input.outputUri)
            putExtra(EXTRA_INITIAL_RECT, input.initialRect)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): CropResult? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        val output = intent.parcelableExtra<Uri>(EXTRA_OUTPUT) ?: return null
        val rect = intent.parcelableExtra<Rect>(EXTRA_CROP_RECT) ?: return null
        return CropResult(
            outputUri = output,
            cropRect = rect,
            originalUri = intent.parcelableExtra<Uri>(EXTRA_SOURCE) ?: output,
        )
    }

    internal companion object {
        const val EXTRA_SOURCE = "crop_source"
        const val EXTRA_ASPECT_W = "crop_aspect_w"
        const val EXTRA_ASPECT_H = "crop_aspect_h"
        const val EXTRA_OUTPUT = "crop_output"
        const val EXTRA_INITIAL_RECT = "crop_initial_rect"
        const val EXTRA_CROP_RECT = "crop_rect"

        /** 从启动 Intent 还原请求；字段缺失时返回 null（调用方按取消处理） */
        fun requestFrom(intent: Intent?): CropRequest? {
            if (intent == null) return null
            val source = intent.parcelableExtra<Uri>(EXTRA_SOURCE) ?: return null
            val output = intent.parcelableExtra<Uri>(EXTRA_OUTPUT) ?: return null
            val aspectW = intent.getIntExtra(EXTRA_ASPECT_W, 0)
            val aspectH = intent.getIntExtra(EXTRA_ASPECT_H, 0)
            if (aspectW <= 0 || aspectH <= 0) return null
            return CropRequest(
                sourceUri = source,
                aspectW = aspectW,
                aspectH = aspectH,
                initialRect = intent.parcelableExtra<Rect>(EXTRA_INITIAL_RECT),
                outputUri = output,
            )
        }

        /** 结果 Intent */
        fun resultIntent(request: CropRequest, cropRect: Rect): Intent = Intent().apply {
            putExtra(EXTRA_SOURCE, request.sourceUri)
            putExtra(EXTRA_OUTPUT, request.outputUri)
            putExtra(EXTRA_CROP_RECT, cropRect)
        }
    }
}

/**
 * 类型安全的 `getParcelableExtra`。
 *
 * Android 13 起 `getParcelableExtra(String, Class)` 才受支持，而 `minSdk` 为 23，
 * 只能继续用被弃用的单参重载（与迁移前 `libs/cropper/ParcelableUtils.kt` 的取舍一致）。
 */
@Suppress("DEPRECATION")
internal inline fun <reified T> Intent.parcelableExtra(key: String): T? =
    getParcelableExtra(key) as? T
