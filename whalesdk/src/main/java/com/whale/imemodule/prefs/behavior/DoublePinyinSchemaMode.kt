package com.whale.imemodule.prefs.behavior

import com.whale.imemodule.prefs.ManagedPreference


enum class DoublePinyinSchemaMode {
    natural,
    flypy,
    abc,
    mspy,
    sogou,
    ziguang;

    companion object : ManagedPreference.StringLikeCodec<DoublePinyinSchemaMode> {
        override fun decode(raw: String) = DoublePinyinSchemaMode.valueOf(raw)
    }
}