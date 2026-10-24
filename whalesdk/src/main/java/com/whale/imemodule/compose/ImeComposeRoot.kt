package com.whale.imemodule.compose

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.AbstractComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
// 这三个 owner 在 lifecycle 2.8+ / savedstate 1.3+ 起只以 View 扩展函数的形式提供
// （旧的 ViewTreeXxxOwner 类已不再是可在 Kotlin 中 import 的声明），必须按扩展函数调用。
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.whale.imemodule.prefs.AppPrefs
import com.whale.imemodule.service.ImeService
import com.whale.imemodule.singleton.EnvironmentSingleton
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

    /**
     * 与 [HardwareCandidateView] 共用：同一 IME 窗口只应有一个 ViewModelStore。
     *
     * 对外可见是刻意的——候选栏复用同一套 owner，避免为同一个窗口建两份
     * `SavedStateRegistry`（那会让 ON_STOP/ON_DESTROY 被重复派发）。
     */
    val viewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    val savedStateRegistryOwner = SavedStateRegistryOwnerImpl(imeLifecycleOwner)

    /** 输入派发 + UI 状态；整个服务生命周期内只有一个 */
    val controller = ImeController(service)

    init {
        setViewTreeLifecycleOwner(imeLifecycleOwner)
        setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
    }

    /**
     * 把三个 ViewTree owner 挂到 IME 窗口的根视图上。
     *
     * Compose 的 `windowRecomposer` 先用 `contentChild` 定位内容子视图，再用
     * `findViewTreeLifecycleOwner()` 从它**向上**遍历。IME 窗口布局
     * （input_method.xml）里 `android.R.id.content` 的直接子级是
     * `com.android.internal.R.id.parentPanel` 那个 LinearLayout，向上直到 DecorView
     * 都没有 owner —— 只在 [init] 里挂在自己身上（子孙节点）是无效的。
     *
     * 必须在 `super.onAttachedToWindow()` 之前：后者会触发
     * `ensureCompositionCreated()` → `windowRecomposer`。
     *
     * 同时恢复旧 `InputView.initNavbarBackground` 的两件事：
     * 1. IME 窗口 edge-to-edge（decorFits=false + 透明/隐藏导航栏）——丢了它系统会在
     *    窗口底部垫一条导航栏底色，把全面屏条的地/剪贴板图标盖出一条异色带；
     * 2. 监听 insets 回写 `systemNavbarWindowsBottom`——键盘总高度、底部栏占位都依赖它。
     */
    override fun onAttachedToWindow() {
        setupImeWindowEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val navbar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            if (navbar != EnvironmentSingleton.instance.systemNavbarWindowsBottom) {
                EnvironmentSingleton.instance.systemNavbarWindowsBottom = navbar
                controller.navbarInset = navbar
                applyPreferredHeight()
            }
            insets
        }
        installImeViewTreeOwners(
            view = this,
            lifecycleOwner = imeLifecycleOwner,
            viewModelStoreOwner = viewModelStoreOwner,
            savedStateRegistryOwner = savedStateRegistryOwner,
        )
        super.onAttachedToWindow()
    }

    /** 等价旧 `initNavbarBackground` 的窗口部分：内容延伸到导航栏之下，避免系统垫底色 */
    private fun setupImeWindowEdgeToEdge() {
        val win = service.window?.window ?: return
        WindowCompat.setDecorFitsSystemWindows(win, false)
        win.insetsController?.apply {
            hide(WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        win.isNavigationBarContrastEnforced = false
    }

    /**
     * 按当前 [EnvironmentSingleton] 尺寸重设视图高度。insets / 旋屏 / 比例调整后
     * `skbAreaHeight` 变化时都要走一次，否则窗口还挂着旧高度。
     */
    fun applyPreferredHeight() {
        layoutParams?.let { lp ->
            val target = preferredKeyboardHeight()
            if (target > 0 && lp.height != target) {
                lp.height = target
                layoutParams = lp
            }
        }
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

/**
 * 把 IME 的三个 ViewTree owner 挂到 [view] 所在窗口的根视图上。
 *
 * 三个 `findViewTreeXxxOwner()` 都是向上遍历的扩展函数，而 IME 窗口不是 Activity，
 * 没有人往它的 DecorView 挂 owner，必须由我们挂。重复调用幂等（覆盖同一 tag）。
 */
internal fun installImeViewTreeOwners(
    view: View,
    lifecycleOwner: LifecycleOwner,
    viewModelStoreOwner: ViewModelStoreOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
) {
    val root = view.rootView
    root.setViewTreeLifecycleOwner(lifecycleOwner)
    root.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
    root.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
}
