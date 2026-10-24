package com.whale.imemodule.prefs.behavior

import com.whale.imemodule.prefs.ManagedPreference


enum class HalfWidthSymbolsMode {
    All,
    OnlyUsed,
    None;

    companion object : ManagedPreference.StringLikeCodec<HalfWidthSymbolsMode> {
        override fun decode(raw: String): HalfWidthSymbolsMode =
            HalfWidthSymbolsMode.valueOf(raw)
    }
}