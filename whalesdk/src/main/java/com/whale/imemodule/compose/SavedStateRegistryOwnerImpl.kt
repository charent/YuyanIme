package com.whale.imemodule.compose

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 供 Compose 使用的 [SavedStateRegistryOwner]。
 *
 * 方案 v1 漏掉了这一项，实测缺少它 `rememberSaveable` 会直接抛
 * `IllegalStateException: ViewTreeSavedStateRegistryOwner not set`。
 *
 * [SavedStateRegistry.attach] 要求 owner 处在 INITIALIZED 阶段，
 * 因此这里在构造时立刻 `performAttach()`；IME 没有跨进程恢复的 `Bundle`，
 * `performRestore(null)` 即可。
 */
class SavedStateRegistryOwnerImpl(
    private val lifecycleOwner: LifecycleOwner
) : SavedStateRegistryOwner {

    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleOwner.lifecycle

    override val savedStateRegistry: SavedStateRegistry
        get() = controller.savedStateRegistry

    init {
        controller.performAttach()
        controller.performRestore(null as Bundle?)
    }
}
