

package com.whale.imemodule.utils

import android.provider.Settings
import com.whale.imemodule.application.Launcher

fun getSecureSettings(name: String): String? {
    return Settings.Secure.getString(Launcher.instance.context.contentResolver, name)
}
