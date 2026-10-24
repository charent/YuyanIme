package com.whale.imemodule.prefs.behavior

import com.whale.imemodule.prefs.ManagedPreference


enum class SymbolMode {
    Symbol,
    Emojicon,
    Emoticon;
    companion object : ManagedPreference.StringLikeCodec<SymbolMode> {
        override fun decode(raw: String) = SymbolMode.valueOf(raw)
    }
}