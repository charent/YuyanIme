package com.whale.imemodule.compose

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 当前输入法控制器。
 *
 * 用 `staticCompositionLocalOf` 而不是 `compositionLocalOf`：控制器在进程内是单例，
 * 引用永不变化，静态版本少一层订阅开销。
 */
val LocalImeController = staticCompositionLocalOf<ImeController> {
    error("ImeController 未提供：ImeApp 必须挂在 ImeComposeRoot 之下")
}
