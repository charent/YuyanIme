package com.yuyan.imemodule.prefs.behavior

import com.yuyan.imemodule.prefs.ManagedPreference


enum class FullDisplayCenterMode {
    MoveCursor,
//    MovePinyin,
    None;

    companion object : ManagedPreference.StringLikeCodec<FullDisplayCenterMode> {
        override fun decode(raw: String): FullDisplayCenterMode =
            FullDisplayCenterMode.valueOf(raw)
    }
}