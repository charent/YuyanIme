package com.whale.inputmethod

import android.system.Os
import android.system.OsConstants

/**
 * 内核页大小兼容检测。
 *
 * 背景：Android 15 起设备可能采用 16KB 页（aarch64 上仍以 4KB 为主），
 * 系统要求 .so 的 `PT_LOAD` 段按页大小对齐，否则 dlopen 直接失败。
 * 本项目的手写三件套（`libhandwriting.so` / `libSogouShell.so` / `libhwInterface.so`）
 * 是闭源预编译产物，仅 4KB（0x1000）对齐，在 16KB 页设备上无法加载，
 * 且没有 16KB 版本可获取，因此只能做「检测 + 禁用入口」的降级处理。
 *
 * `libwhaleime.so`（0x4000）与 `libgpen_handwriter.so`（0x10000）已达标，不受影响。
 */
object NativePageCompat {

    /**
     * 当前内核页大小（字节）。`Os.sysconf` 自 API 21 起可用；
     * 任何异常都按 4096 处理，即「按老设备对待」，不会误禁用手写。
     */
    val pageSize: Long = runCatching {
        Os.sysconf(OsConstants._SC_PAGESIZE)
    }.getOrDefault(4096L)

    /** 是否为 16KB（或更大）页设备 */
    val is16KbDevice: Boolean = pageSize > 4096L
}
