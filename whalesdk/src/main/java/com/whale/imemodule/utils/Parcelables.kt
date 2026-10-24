package com.whale.imemodule.utils

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable

// Android 13 起才有 getParcelable(key, Class) 的类型安全重载，minSdk 为 23 时只能继续用
// 被弃用的单参版本（与迁移前 libs/cropper/ParcelableUtils.kt 的取舍一致）。

@Suppress("DEPRECATION")
inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? = getParcelable(key) as? T

@Suppress("DEPRECATION")
inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? = getParcelableExtra(key) as? T
