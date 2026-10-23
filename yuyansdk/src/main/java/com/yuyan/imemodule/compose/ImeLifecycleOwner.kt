package com.yuyan.imemodule.compose

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * 输入法自己的 [LifecycleOwner]。
 *
 * IME 不是 Activity/Fragment，系统不会提供 `ViewTreeLifecycleOwner`，
 * 而 Compose 的 Recomposer（`WindowRecomposerFactory.LifecycleAware`）、
 * `ViewModel`、`rememberSaveable` 全都依赖它，因此必须自建并挂到根视图上。
 *
 * 状态机（与方案 §2.1 一致）：
 * ```
 * INITIALIZED --onCreate--> CREATED --show--> RESUMED
 *                              ^---hide-----/
 * 任意状态 --destroy--> DESTROYED（仅一次）
 * ```
 * 注意：键盘收起只降到 CREATED 而**不销毁组合**，因为重建组合的开销大于常驻；
 * 只有服务 `onDestroy` 才真正销毁。收起时降到 CREATED 还能正常派发 ON_STOP，
 * 从而让 `rememberSaveable` 把状态存进 SavedStateRegistry、下次显示时取回。
 */
class ImeLifecycleOwner : LifecycleOwner {

    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = registry

    init {
        registry.currentState = Lifecycle.State.INITIALIZED
    }

    /** 服务 onCreate：组合可以开始建立 */
    fun onCreate() = moveTo(Lifecycle.State.CREATED)

    /** 键盘显示：onStartInputView / onWindowShown */
    fun show() = moveTo(Lifecycle.State.RESUMED)

    /** 键盘收起：onFinishInputView / onWindowHidden，组合保留 */
    fun hide() = moveTo(Lifecycle.State.CREATED)

    /** 服务销毁，只能走一次 */
    fun destroy() = moveTo(Lifecycle.State.DESTROYED)

    private fun moveTo(state: Lifecycle.State) {
        val current = registry.currentState
        // DESTROYED 是终态，继续 set 会抛 "no event down from DESTROYED"
        if (current == Lifecycle.State.DESTROYED) return
        registry.currentState = state
    }
}
