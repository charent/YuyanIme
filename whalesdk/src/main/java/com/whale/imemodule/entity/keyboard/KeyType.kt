package com.whale.imemodule.entity.keyboard

import com.whale.imemodule.prefs.ManagedPreference

enum class KeyType {
    AccentKey,
    Function,
    Normal;

    companion object : ManagedPreference.StringLikeCodec<KeyType> {
        override fun decode(raw: String): KeyType =
            KeyType.valueOf(raw)
    }
}