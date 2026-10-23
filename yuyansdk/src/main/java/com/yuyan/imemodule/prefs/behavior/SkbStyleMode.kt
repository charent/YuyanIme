package com.yuyan.imemodule.prefs.behavior

import com.yuyan.imemodule.prefs.ManagedPreference


enum class SkbStyleMode {
    Google,
    Samsung,
    Yuyan;
    companion object : ManagedPreference.StringLikeCodec<SkbStyleMode> {
        override fun decode(raw: String) = SkbStyleMode.valueOf(raw)
    }
}