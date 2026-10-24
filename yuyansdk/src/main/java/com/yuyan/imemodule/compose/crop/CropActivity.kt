package com.yuyan.imemodule.compose.crop

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.settings.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 裁剪宿主 Activity（Compose，替代 vendored 的 `libs/cropper/CropImageActivity`）。
 *
 * 只做三件事：还原 [CropRequest]、解码源图、把结果写回 `RESULT_OK`。
 * 交互与绘制全部在 [CropScreen]。
 *
 * 沉浸式深色底与浅色系统栏图标是固定策略：图像编辑需要中性背景，跟随浅色主题反而
 * 干扰对图像的判断；Material 3 控件仍走 [SettingsTheme]，与设置页保持一致。
 */
class CropActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = CropContract.requestFrom(intent)
        if (request == null) {
            finish()
            return
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            SettingsTheme {
                CropRoute(
                    request = request,
                    onCancel = { finish() },
                    onConfirm = { cropRect -> finishWithResult(request, cropRect) },
                )
            }
        }
    }

    private fun finishWithResult(request: CropRequest, cropRect: Rect) {
        setResult(RESULT_OK, CropContract.resultIntent(request, cropRect))
        finish()
    }
}

@Composable
private fun CropRoute(
    request: CropRequest,
    onCancel: () -> Unit,
    onConfirm: (Rect) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }

    LaunchedEffect(request) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { CropBitmaps.load(context, request.sourceUri) }.getOrNull()
        }
        if (loaded == null) loadFailed = true else image = loaded
    }

    // 解码失败按取消处理：调用方据此保留原背景 / 放弃新建
    LaunchedEffect(loadFailed) {
        if (loadFailed) {
            Toast.makeText(context, R.string.crop_load_failed, Toast.LENGTH_SHORT).show()
            onCancel()
        }
    }

    val source = image
    if (source == null) {
        CropLoadingScreen(onCancel = onCancel)
        return
    }

    val imageBitmap = remember(source) { source.asImageBitmap() }
    CropScreen(
        image = imageBitmap,
        request = request,
        processing = processing,
        onCancel = onCancel,
        onConfirm = confirm@{ cropRect ->
            if (processing) return@confirm
            processing = true
            scope.launch {
                val ok = withContext(Dispatchers.Default) {
                    runCatching {
                        val cropped = CropBitmaps.crop(
                            source = source,
                            rect = cropRect,
                            outW = request.aspectW,
                            outH = request.aspectH,
                        )
                        try {
                            CropBitmaps.writePng(context, cropped, request.outputUri)
                        } finally {
                            cropped.recycle()
                        }
                    }.isSuccess
                }
                if (ok) {
                    onConfirm(cropRect)
                } else {
                    processing = false
                    Toast.makeText(context, R.string.crop_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        },
    )
}
