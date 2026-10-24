package com.whale.imemodule.prefs.behavior

import com.whale.imemodule.prefs.ManagedPreference


enum class SkbStyleMode {
    Google,
    Samsung,
    Whale;
    companion object : ManagedPreference.StringLikeCodec<SkbStyleMode> {
        override fun decode(raw: String) = SkbStyleMode.valueOf(raw)
    }
}