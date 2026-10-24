package com.whale.imemodule.utils

import com.whale.imemodule.application.Launcher
import java.io.File

inline fun <T> withTempDir(block: (File) -> T): T {
    val dir = Launcher.instance.context.cacheDir.resolve(System.currentTimeMillis().toString()).also {
        it.mkdirs()
    }
    try {
        return block(dir)
    } finally {
        dir.deleteRecursively()
    }
}
