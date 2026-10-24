package com.yuyan.imemodule.compose

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.yuyan.imemodule.compose.keyboard.CandidateBar
import com.yuyan.imemodule.service.DictDecoder
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import kotlin.math.max

/**
 * 物理键盘模式下的候选栏（`onCreateCandidatesView` 的 Compose 版本）。
 *
 * 旧实现是 `candidate/CandidateView` + `FloatCandidateBar`：候选栏随光标位置浮动，
 * 位置由 `onUpdateCursorAnchorInfo` 的锚点算出。这里复用同一套定位数学，
 * 只是渲染换成 Compose 的 `CandidateBar`，所以两端的候选交互完全一致。
 *
 * 外层刻意用 `FrameLayout` 而不是直接继承 `AbstractComposeView`：
 * 后者把 `onMeasure` 声明为 final，无法按「屏幕宽度 × 候选区高度」定尺寸。
 *
 * 三个 ViewTree owner 由 [ImeComposeRoot] 传入：`SavedStateRegistryOwnerImpl` 只能在
 * 生命周期处于 INITIALIZED 时构造，而 `ImeService.onCreate()` 预热根视图时已把它推到
 * CREATED，此处新建必然抛
 * `IllegalStateException: Restarter must be created only during owner's initialization stage`。
 */
class HardwareCandidateView(
    context: Context,
    private val controller: ImeController,
    private val imeLifecycleOwner: ImeLifecycleOwner,
    private val viewModelStoreOwner: ViewModelStoreOwner,
    private val savedStateRegistryOwner: SavedStateRegistryOwner,
) : FrameLayout(context) {

    private val composeView = object : AbstractComposeView(context) {
        override val shouldCreateCompositionOnAttachedToWindow: Boolean
            get() = true

        @Composable
        override fun Content() {
            CompositionLocalProvider(
                LocalLifecycleOwner provides imeLifecycleOwner,
                LocalImeController provides controller,
            ) {
                ImeTheme {
                    val type by controller.keyboardType.collectAsStateWithLifecycle()
                    val uiState by controller.state.collectAsStateWithLifecycle()
                    val candidateState by DictDecoder.candidateState.collectAsStateWithLifecycle()
                    val env = EnvironmentSingleton.instance
                    val density = LocalDensity.current
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(density) { env.heightForCandidatesArea.toDp() })
                            .background(Color.Transparent),
                    ) {
                        CandidateBar(
                            keyboardType = type,
                            uiState = uiState,
                            controller = controller,
                            candidateState = candidateState,
                        )
                    }
                }
            }
        }

        init {
            setViewTreeLifecycleOwner(imeLifecycleOwner)
            setViewTreeViewModelStoreOwner(this@HardwareCandidateView.viewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(this@HardwareCandidateView.savedStateRegistryOwner)
        }

        /**
         * 与 [ImeComposeRoot] 同一理由：windowRecomposer 从 `contentChild` 向上找
         * ViewTreeLifecycleOwner，必须先挂到窗口根上。物理键盘分支下 `setInputView`
         * 可能整个被跳过，主根不会先挂 owner，候选栏必须自己兜住。
         */
        override fun onAttachedToWindow() {
            installImeViewTreeOwners(
                view = this,
                lifecycleOwner = imeLifecycleOwner,
                viewModelStoreOwner = this@HardwareCandidateView.viewModelStoreOwner,
                savedStateRegistryOwner = this@HardwareCandidateView.savedStateRegistryOwner,
            )
            super.onAttachedToWindow()
        }

        /** 暴露给外层做销毁（`disposeComposition` 是 protected） */
        fun disposeCompose() {
            disposeComposition()
        }
    }

    private var horizontalCutoutWidth = 0

    init {
        setBackgroundColor(AndroidColor.TRANSPARENT)
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** 与旧 `CandidateView.updatePosition` 同一套定位数学 */
    fun updatePosition(anchor: FloatArray) {
        val env = EnvironmentSingleton.instance
        val bottom = env.mScreenHeight - anchor[1].toInt()
        val diffHeight = (env.heightForCandidatesArea * 1.5).toInt()
        val density = resources.displayMetrics.density
        val left = if (!env.isLandscape) 0
        else if (horizontalCutoutWidth - anchor[0] > floatingWidth) anchor[0].toInt() - (20 * density).toInt()
        else horizontalCutoutWidth - floatingWidth
        setPadding(
            max(0, left), 0, 0,
            if (bottom > diffHeight) bottom - diffHeight else bottom + env.heightForCandidatesArea
        )
        requestLayout()
    }

    private val floatingWidth: Int
        get() {
            val env = EnvironmentSingleton.instance
            val density = resources.displayMetrics.density
            return ((if (env.isLandscape) env.mScreenHeight else env.mScreenWidth) - (40 * density).toInt())
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val env = EnvironmentSingleton.instance
        horizontalCutoutWidth = resources.displayMetrics.widthPixels
        val height = (env.heightForCandidatesArea * 1.2f).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(floatingWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }

    fun destroy() {
        composeView.disposeCompose()
        // ViewModelStore 与 ImeComposeRoot 共用，不能在这里 clear：
        // ImeService.onDestroy 的顺序是先 destroy 候选栏再销毁主根，
        // 提前 clear 会让主组合在销毁前拿到空 store。
        removeAllViews()
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
}
