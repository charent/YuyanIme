package com.whale.imemodule.prefs.behavior

import com.whale.imemodule.prefs.ManagedPreference


enum class KeyboardSymbolSlideUpMod {
    SHORT,
    MEDIUM,
    LONG;
    companion object : ManagedPreference.StringLikeCodec<KeyboardSymbolSlideUpMod> {
        override fun decode(raw: String) = KeyboardSymbolSlideUpMod.valueOf(raw)
    }
}