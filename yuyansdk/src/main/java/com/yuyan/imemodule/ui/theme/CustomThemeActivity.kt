package com.yuyan.imemodule.ui.theme

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.settings.KeyboardPreview
import com.yuyan.imemodule.compose.settings.SettingsPage
import com.yuyan.imemodule.compose.settings.SettingsRow
import com.yuyan.imemodule.compose.settings.SettingsSwitchRow
import com.yuyan.imemodule.compose.settings.SettingsTheme
import com.yuyan.imemodule.compose.settings.string
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeFilesManager
import com.yuyan.imemodule.data.theme.ThemePreset
import com.yuyan.imemodule.libs.cropper.CropImageContract
import com.yuyan.imemodule.libs.cropper.CropImageContractOptions
import com.yuyan.imemodule.libs.cropper.CropImageOptions
import com.yuyan.imemodule.libs.cropper.CropImageView
import com.yuyan.imemodule.libs.cropper.parcelable
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import splitties.resources.styledColor
import java.io.File

/**
 * 自定义主题编辑（背景图裁剪 + 亮度 + 明暗键帽）。
 *
 * 方案 D4：裁剪流程（`CropImageContract` + `CropImageView`）原样保留，
 * 只有外层界面与预览改为 Compose —— 预览用 [KeyboardPreview]，与键盘真实渲染同源。
 */
class CustomThemeActivity : ComponentActivity() {

    sealed interface BackgroundResult : Parcelable {
        @Parcelize
        data class Updated(val theme: Theme.Custom) : BackgroundResult

        @Parcelize
        data class Created(val theme: Theme.Custom) : BackgroundResult

        @Parcelize
        data class Deleted(val name: String) : BackgroundResult
    }

    class Contract : ActivityResultContract<Theme.Custom?, BackgroundResult?>() {
        override fun createIntent(context: Context, input: Theme.Custom?): Intent =
            Intent(context, CustomThemeActivity::class.java).apply {
                putExtra(ORIGIN_THEME, input)
            }

        override fun parseResult(resultCode: Int, intent: Intent?): BackgroundResult? =
            intent?.parcelable(RESULT)
    }

    private val viewModel: CustomThemeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.init(intent)
        enableEdgeToEdge()
        setContent {
            SettingsTheme {
                CustomThemeScreen(
                    viewModel = viewModel,
                    onCancel = { finishWith(null, Activity.RESULT_CANCELED) },
                    onDelete = { finishWith(BackgroundResult.Deleted(viewModel.themeName), Activity.RESULT_OK) },
                    onDone = { result -> finishWith(result, Activity.RESULT_OK) },
                )
            }
        }
    }

    private fun finishWith(result: BackgroundResult?, resultCode: Int) {
        setResult(resultCode, Intent().apply { putExtra(RESULT, result) })
        finish()
    }

    companion object {
        const val RESULT = "result"
        const val ORIGIN_THEME = "origin_theme"
    }
}

/**
 * 编辑态（旋转不丢）。
 *
 * 所有涉及文件的字段只在这里改，Composable 只读 + 调用方法，
 * 保证「裁剪结果 → 写盘 → 回传」这条链与旧实现逐句对应。
 */
class CustomThemeViewModel : ViewModel() {

    var theme by mutableStateOf<Theme.Custom?>(null)
        private set
    var darkKeys by mutableStateOf(false)
        private set
    var brightness by mutableIntStateOf(70)
        private set
    var newCreated = true
        private set
    var hasBackground = false
        private set
    var saving by mutableStateOf(false)
        private set

    var tempImageFile: File? = null
    var srcImageExtension: String? = null
    var srcImageBuffer: ByteArray? = null
    var cropRect: Rect? = null
    var croppedBitmap: Bitmap? = null

    private var croppedImageFile: File? = null
    private var srcImageFile: File? = null
    private var initialized = false

    val themeName: String get() = theme?.name.orEmpty()

    fun init(intent: Intent?) {
        if (initialized) return
        initialized = true
        val origin = intent?.parcelable<Theme.Custom>(CustomThemeActivity.ORIGIN_THEME)
        if (origin != null) {
            theme = origin
            newCreated = false
            val bg = origin.backgroundImage
            hasBackground = bg != null
            if (bg != null) {
                brightness = bg.brightness
                cropRect = bg.cropRect
                croppedImageFile = File(bg.croppedFilePath)
                srcImageFile = File(bg.srcFilePath)
                croppedBitmap = BitmapFactory.decodeFile(bg.croppedFilePath)
            }
            darkKeys = !origin.isDark
        } else {
            val (name, cropped, src) = ThemeFilesManager.newCustomBackgroundImages()
            croppedImageFile = cropped
            srcImageFile = src
            theme = ThemePreset.TransparentDark.deriveCustomBackground(name, cropped.path, src.path)
            hasBackground = true
            darkKeys = false
            brightness = 70
        }
    }

    /** 裁剪结果落到内存态；写盘在保存时统一做（与旧实现一致） */
    fun onCropped(bitmap: Bitmap?, rect: Rect?, extension: String? = null, buffer: ByteArray? = null) {
        if (bitmap != null) {
            croppedBitmap = bitmap
            cropRect = rect
        }
        if (extension != null) srcImageExtension = extension
        if (buffer != null) srcImageBuffer = buffer
        rebuildTheme()
    }

    fun updateDarkKeys(value: Boolean) {
        darkKeys = value
        rebuildTheme()
    }

    fun updateBrightness(value: Int) {
        brightness = value
        rebuildTheme()
    }

    private fun rebuildTheme() {
        val current = theme ?: return
        val cropped = croppedImageFile ?: return
        val src = srcImageFile ?: return
        val base = if (darkKeys) ThemePreset.TransparentLight else ThemePreset.TransparentDark
        theme = base.deriveCustomBackground(
            current.name,
            cropped.path,
            src.path,
            brightness,
            cropRect,
        )
    }

    fun prepareTempFile(cacheDir: File): File {
        val existing = tempImageFile
        if (existing != null && existing.exists()) return existing
        return File.createTempFile("cropped", ".png", cacheDir).also { tempImageFile = it }
    }

    fun srcFile(): File? = srcImageFile

    fun cropOptions(context: Context, w: Int, h: Int): CropImageOptions = CropImageOptions(
        initialCropWindowRectangle = cropRect,
        guidelines = CropImageView.Guidelines.ON_TOUCH,
        borderLineColor = Color.WHITE,
        borderLineThickness = 1f,
        borderCornerColor = Color.WHITE,
        borderCornerOffset = 0f,
        imageSourceIncludeGallery = true,
        imageSourceIncludeCamera = false,
        aspectRatioX = w,
        aspectRatioY = h,
        fixAspectRatio = true,
        customOutputUri = tempImageFile?.toUri(),
        outputCompressFormat = Bitmap.CompressFormat.PNG,
        cropMenuCropButtonIcon = R.drawable.ic_menu_done,
        showProgressBar = true,
        progressBarColor = context.styledColor(android.R.attr.colorAccent),
        activityMenuIconColor = context.styledColor(android.R.attr.colorControlNormal),
        activityMenuTextColor = context.styledColor(android.R.attr.colorForeground),
        activityBackgroundColor = context.styledColor(android.R.attr.colorBackground),
        toolbarColor = context.styledColor(android.R.attr.colorPrimary),
        toolbarBackButtonColor = context.styledColor(android.R.attr.colorControlNormal),
    )

    /** 保存：写裁剪图/原图，产出回传结果 */
    fun buildResult(): CustomThemeActivity.BackgroundResult? {
        val current = theme ?: return null
        val bg = current.backgroundImage
        if (bg != null) {
            val croppedFile = croppedImageFile
            val bitmap = croppedBitmap
            if (croppedFile != null && bitmap != null) {
                croppedFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            if (newCreated) {
                var src = srcImageFile
                val ext = srcImageExtension
                if (src != null && ext != null) {
                    src = File("${src.absolutePath}.$ext")
                    srcImageFile = src
                }
                val buffer = srcImageBuffer
                if (src != null && buffer != null) src.writeBytes(buffer)
            }
            val newTheme = current.copy(
                backgroundImage = bg.copy(
                    srcFilePath = srcImageFile?.absolutePath ?: bg.srcFilePath,
                    brightness = brightness,
                    cropRect = cropRect,
                )
            )
            return if (newCreated) CustomThemeActivity.BackgroundResult.Created(newTheme)
            else CustomThemeActivity.BackgroundResult.Updated(newTheme)
        }
        return if (newCreated) CustomThemeActivity.BackgroundResult.Created(current)
        else CustomThemeActivity.BackgroundResult.Updated(current)
    }

    fun save(onResult: (CustomThemeActivity.BackgroundResult?) -> Unit) {
        saving = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                tempImageFile?.delete()
                runCatching { buildResult() }.getOrNull()
            }
            saving = false
            onResult(result)
        }
    }

    fun cancel() {
        tempImageFile?.delete()
    }
}

@Composable
private fun CustomThemeScreen(
    viewModel: CustomThemeViewModel,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onDone: (CustomThemeActivity.BackgroundResult?) -> Unit,
) {
    val context = LocalContext.current
    val env = remember { EnvironmentSingleton.instance }
    val scope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var croppedOnce by remember { mutableStateOf(!viewModel.newCreated) }

    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (!result.isSuccessful) {
            // 新建时取消裁剪 = 放弃整个编辑；编辑已有主题时取消只是不改背景
            if (viewModel.newCreated) onCancel()
            return@rememberLauncherForActivityResult
        }
        val bitmap = result.getBitmap(context)?.let {
            Bitmap.createScaledBitmap(it, env.skbWidth, env.inputAreaHeight, true)
        }
        val extension = result.originalUri?.let { uri ->
            MimeTypeMap.getSingleton().getExtensionFromMimeType(context.contentResolver.getType(uri))
        }
        val buffer = result.originalUri?.let { uri ->
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }
        viewModel.onCropped(bitmap, result.cropRect, extension, buffer)
        croppedOnce = true
    }

    fun launchCrop() {
        scope.launch {
            val temp = withContext(Dispatchers.IO) {
                viewModel.prepareTempFile(context.cacheDir)
            }
            cropLauncher.launch(
                CropImageContractOptions(
                    uri = viewModel.srcFile()?.takeIf { it.exists() }?.toUri(),
                    cropImageOptions = viewModel.cropOptions(context, env.skbWidth, env.inputAreaHeight),
                )
            )
        }
    }

    // 新建主题：进入即拉起裁剪（旧实现同样在 onCreate 末尾自动调用）
    LaunchedEffect(Unit) {
        if (viewModel.newCreated && !croppedOnce) launchCrop()
    }

    BackHandler { viewModel.cancel(); onCancel() }

    Box(modifier = Modifier.fillMaxSize()) {
        SettingsPage(
            title = string(if (viewModel.newCreated) R.string.new_theme else R.string.edit_theme),
            onBack = {
                viewModel.cancel()
                onCancel()
            },
            actions = {
                if (!viewModel.newCreated) {
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text(string(R.string.delete))
                    }
                }
                TextButton(onClick = {
                    viewModel.save { result -> onDone(result) }
                }) { Text(string(R.string.save)) }
            },
        ) {
            val theme = viewModel.theme
            if (theme != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    KeyboardPreview(theme = theme, scale = 0.7f)
                }
            }
            if (viewModel.hasBackground) {
                if (!viewModel.newCreated) {
                    SettingsRow(
                        title = string(R.string.recrop_image),
                        showArrow = true,
                        onClick = { launchCrop() },
                    )
                }
                SettingsSwitchRow(
                    title = string(R.string.dark_keys),
                    summary = null,
                    checked = viewModel.darkKeys,
                    enabled = true,
                    onCheckedChange = { viewModel.updateDarkKeys(it) },
                )
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(
                        text = "${string(R.string.brightness)}: ${viewModel.brightness}%",
                        fontSize = 15.sp,
                    )
                    Slider(
                        value = viewModel.brightness.toFloat(),
                        onValueChange = { viewModel.updateBrightness(it.toInt()) },
                        valueRange = 0f..100f,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (viewModel.saving) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(0.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(string(R.string.delete_theme)) },
            text = { Text(string(R.string.delete_theme_msg).format(viewModel.themeName)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.cancel()
                    onDelete()
                }) { Text(string(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(string(R.string.cancel))
                }
            },
        )
    }
}

