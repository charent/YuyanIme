package com.whale.imemodule.prefs.behavior

import com.whale.imemodule.prefs.ManagedPreference


enum class ClipboardLayoutMode {
    ListView,
    GridView,
    FlexboxView;

    companion object : ManagedPreference.StringLikeCodec<ClipboardLayoutMode> {
        override fun decode(raw: String): ClipboardLayoutMode =
            ClipboardLayoutMode.valueOf(raw)
    }
}