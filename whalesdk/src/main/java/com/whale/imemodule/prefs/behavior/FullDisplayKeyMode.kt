package com.whale.imemodule.prefs.behavior

import com.whale.imemodule.prefs.ManagedPreference


enum class FullDisplayKeyMode {
    SwitchIme,
    SwitchLanguage,
    Clipboard,
    Phrases,
    None;

    companion object : ManagedPreference.StringLikeCodec<FullDisplayKeyMode> {
        override fun decode(raw: String): FullDisplayKeyMode =
            FullDisplayKeyMode.valueOf(raw)
    }
}