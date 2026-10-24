package com.whale.imemodule.prefs.behavior

import com.whale.imemodule.prefs.ManagedPreference


enum class KeyboardOneHandedMod {
    LEFT,
    RIGHT;
    companion object : ManagedPreference.StringLikeCodec<KeyboardOneHandedMod> {
        override fun decode(raw: String) = KeyboardOneHandedMod.valueOf(raw)
    }
}