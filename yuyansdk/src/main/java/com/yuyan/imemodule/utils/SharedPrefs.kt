package com.yuyan.imemodule.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * 默认 SharedPreferences 的读取入口。
 *
 * 原先用 `androidx.preference.PreferenceManager.getDefaultSharedPreferences()`，
 * Phase 5 移除了 androidx.preference 依赖，这里按同样的命名规则直接取：
 * 文件名固定为 `<packageName>_preferences`，保证**历史数据继续可读**。
 * 该命名规则一旦改动，用户所有设置/词库索引都会"丢失"（实际是读到了新文件）。
 */
fun Context.defaultSharedPreferences(): SharedPreferences =
    getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
