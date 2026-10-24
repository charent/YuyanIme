package com.whale.imemodule.utils

import android.content.pm.PackageInfo

val PackageInfo.versionCodeCompat: Long
    get() = longVersionCode
