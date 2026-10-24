package com.whale.imemodule.prefs.behavior

import com.whale.imemodule.prefs.ManagedPreference


enum class PopupMenuMode {
    Text,
    Clear,
    SwitchIME,
    EnglishCell,
    Revertl,
    Move,
    Enter,
    EMOJI,
    None;

    companion object : ManagedPreference.StringLikeCodec<PopupMenuMode> {
        override fun decode(raw: String) = PopupMenuMode.valueOf(raw)
    }
}