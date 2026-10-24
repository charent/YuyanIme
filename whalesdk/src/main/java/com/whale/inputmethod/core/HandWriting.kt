package com.whale.inputmethod.core

import android.content.Context
import android.util.Log
import androidx.annotation.Keep

/**
 * 手写引擎 JNI 门面。
 *
 * 注意：`libhandwriting.so` 是闭源预编译产物，仅按 4KB 页对齐，
 * 在 16KB 页设备上 `System.loadLibrary` 会抛 [UnsatisfiedLinkError]。
 * 历史实现把 loadLibrary 放在 `init` 块里且不捕获异常，会导致整个 object 初始化失败，
 * 首次抬笔时以 `ExceptionInInitializerError` 崩溃。
 *
 * 因此这里改为惰性、可重试的受保护加载：所有 external 调用前先经过 [ensureLoaded]，
 * 加载失败时返回安全默认值，由调用方（[com.whale.inputmethod.HWEngine]）负责降级到「不可用」。
 */
object HandWriting {

    private const val TAG = "HandWriting"

    @Volatile
    private var loadResult: Boolean? = null

    private var initialized = false

    /**
     * 保证本地库已加载。返回值恒定（首次结果被缓存），可安全重复调用。
     */
    fun ensureLoaded(): Boolean {
        loadResult?.let { return it }
        val result = try {
            System.loadLibrary("handwriting")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libhandwriting.so 加载失败（16KB 页设备不支持 4KB 对齐库）", e)
            false
        } catch (e: RuntimeException) {
            // 兜底：个别 ROM 的加载失败会包成其他运行时异常
            Log.w(TAG, "libhandwriting.so 加载异常", e)
            false
        }
        loadResult = result
        return result
    }

    val isLoaded: Boolean
        get() = loadResult == true

    fun init(context: Context): Boolean {
        if (!ensureLoaded()) return false
        if (this.initialized) return true
        val result = runCatching {
            initWithDirectory(context, context.getExternalFilesDir("hw").toString())
        }.getOrDefault(false)
        this.initialized = result
        return result
    }

    fun setProperties(): Boolean {
        if (!ensureLoaded()) return false
        return runCatching { reloadConfig() }.getOrDefault(false)
    }

    fun selectInputMode(i: Int): Boolean {
        if (!ensureLoaded()) return false
        return runCatching { activeMode(i) }.getOrDefault(false)
    }

    @Throws(NumberFormatException::class)
    fun getCandidatesPyComposition(): Array<Array<String?>?> {
        if (!ensureLoaded()) return emptyArray()
        return getCandidates()
    }

    /** 清空笔迹缓冲区。加载失败时静默跳过。 */
    fun resetStrokes() {
        if (!ensureLoaded()) return
        runCatching { reset() }
    }

    /** 提交笔迹点序列。加载失败时返回 false。 */
    fun submitPoints(points: IntArray?): Boolean {
        if (!ensureLoaded()) return false
        return runCatching { inputHWPoints(points) }.getOrDefault(false)
    }

    @Keep
    external fun getPackageName(): String?

    external fun activeMode(mode: Int): Boolean

    external fun getCandidates(): Array<Array<String?>?>

    external fun initWithDirectory(context: Context, str: String?): Boolean

    external fun inputHWPoints(iArr: IntArray?): Boolean

    external fun release()

    external fun reloadConfig(): Boolean

    external fun reset(): Boolean
}
