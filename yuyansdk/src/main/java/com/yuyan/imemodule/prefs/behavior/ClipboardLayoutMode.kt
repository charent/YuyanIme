package com.yuyan.imemodule.prefs.behavior

import com.yuyan.imemodule.prefs.ManagedPreference


enum class ClipboardLayoutMode {
    ListView,
    GridView,
    FlexboxView;

    companion object : ManagedPreference.StringLikeCodec<ClipboardLayoutMode> {
        override fun decode(raw: String): ClipboardLayoutMode =
            ClipboardLayoutMode.valueOf(raw)
    }
}