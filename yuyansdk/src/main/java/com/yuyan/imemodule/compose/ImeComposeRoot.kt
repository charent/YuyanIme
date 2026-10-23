package com.yuyan.imemodule.compose

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.AbstractComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
// 这三个 owner 在 lifecycle 2.8+ / savedstate 1.3+ 起只以 View 扩展函数的形式提供
// （旧的 ViewTreeXxxOwner 类已不再是可在 Kotlin 中 import 的声明），必须按扩展函数调用。
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.service.ImeService
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import kotlin.math.max

/**
 * IME 的 Compose 根视图。
 *
 * `InputMethodService` 只要求 `onCreateInputView()` 返回一个 `View`，
 * 所以这里用 [AbstractComposeView] 作为接缝。三个 ViewTree owner 缺一不可：
 * - `ViewTreeLifecycleOwner`：Recomposer 靠它自动解析并启停；缺失即无内容/崩溃
 * - `ViewTreeViewModelStoreOwner`：缺失时 `viewModel()` 抛异常
 * - `ViewTreeSavedStateRegistryOwner`：缺失时 `rememberSaveable` 抛异常
 *
 * Recomposer 不需要手工创建：视图 attach 到 IME 自有窗口后，
 * `WindowRecomposerFactory.LifecycleAware` 会依据上面的 owner 自行创建。
 */
class ImeComposeRoot @JvmOverloads constructor(
    context: Context,
    private val service: ImeService,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {

    val imeLifecycleOwner = ImeLifecycleOwner()

    private val viewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    private val savedStateRegistryOwner = SavedStateRegistryOwnerImpl(imeLifecycleOwner)

    /** 输入派发 + UI 状态；整个服务生命周期内只有一个 */
    val controller = ImeController(service)

    init {
        setViewTreeLifecycleOwner(imeLifecycleOwner)
        setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
    }

    /**
     * 保证 attach 即建组合。
     *
     * 该属性在 `AbstractComposeView` 里是 **只读的 protected val**，只能覆写不能赋值；
     * 其默认值随 Compose 版本变化（`ComposeView` 为 true，`AbstractComposeView` 为 false），
     * 显式覆写可避免不同版本行为差异导致的「attach 后一直不出内容」。
     */
    override val shouldCreateCompositionOnAttachedToWindow: Boolean
        get() = true

    @Composable
    override fun Content() {
        CompositionLocalProvider(
            LocalLifecycleOwner provides imeLifecycleOwner,
            LocalImeController provides controller,
        ) {
            ImeApp()
        }
    }

    /**
     * 供 `ImeService.setInputView` 使用的期望高度（像素）。
     *
     * 旧 `InputView` 自己按 `EnvironmentSingleton` 算好高度并返回精确的 measured 尺寸；
     * Compose 根没有这些子 View，若不显式指定高度就会被设成 `MATCH_PARENT`
     * 从而把整个 IME 窗口撑满，进而让 `onComputeInsets` 报出错误的键盘区域。
     *
     * 悬浮模式必须占满整个可用区域（键盘由内部 padding 定位、也要能拖到屏幕上半部），
     * 其余模式取 `skbAreaHeight`（候选区 + 键区 + 间隔 + 全面屏条 + 导航栏）。
     */
    fun preferredKeyboardHeight(): Int {
        val env = EnvironmentSingleton.instance
        return if (env.keyboardModeFloat) ViewGroup.LayoutParams.MATCH_PARENT else env.skbAreaHeight
    }

    /** 等价于 `IKeyboardView.getKeyboardRect()`，供 `onComputeInsets` 使用 */
    fun getKeyboardRect(): IntArray {
        val env = EnvironmentSingleton.instance
        if (!env.keyboardModeFloat) {
            val location = IntArray(2).also { getLocationInWindow(it) }
            return intArrayOf(location[0], location[1], width, height)
        }
        // 悬浮模式：窗口仍是整块，键盘只是其中一个子区域，必须回报子区域的坐标，
        // 否则 touchableRegion 会覆盖全屏，宿主应用上方区域将无法点击
        val internal = AppPrefs.getInstance().internal
        val rightPadding = if (env.isLandscape) internal.keyboardRightPaddingLandscapeFloat.getValue()
        else internal.keyboardRightPaddingFloat.getValue()
        val bottomPadding = if (env.isLandscape) internal.keyboardBottomPaddingLandscapeFloat.getValue()
        else internal.keyboardBottomPaddingFloat.getValue()
        val w = env.skbWidth
        val h = env.inputAreaHeight + env.heightForKeyboardMove
        val left = max(0, width - rightPadding - w)
        val top = max(0, height - bottomPadding - h)
        return intArrayOf(left, top, w, h)
    }

    /** 仅由服务 `onDestroy` 调用，之后该实例不可再用 */
    fun destroyComposition() {
        disposeComposition()
        viewModelStoreOwner.viewModelStore.clear()
        imeLifecycleOwner.destroy()
    }
}
