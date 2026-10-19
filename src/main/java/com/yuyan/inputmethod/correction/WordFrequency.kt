package com.yuyan.inputmethod.correction

import android.content.Context
import android.util.Log

/**
 * 中文词频表（只读），用于给纠错候选排序。
 *
 * 为什么需要它：纠错常常同时召回好几个「也说得通」的编码（`bihao` 的 `ni'hao` / `bi'yao` / `bi'gao`），
 * 词库只告诉我们这些编码下各有哪些候选，**不告诉我们哪个词更常用**。没有词频就只能靠
 * 「候选数量」这种粗糙的兜底信号，`bi'gao` 偶尔也会混进来。
 *
 * 数据来自 `assets/pinyin_correction/word_freq.txt`（jieba 词典裁剪：2~6 个汉字、词频前 3 万条，
 * 约 355 KB，纯文本，按词频降序）。启动时在子线程载入，载入前 [freq] 一律返回 0（调用方走兜底逻辑）。
 */
object WordFrequency {

    private const val TAG = "PinyinCorrection"

    const val ASSET_PATH = "pinyin_correction/word_freq.txt"

    @Volatile
    private var table: HashMap<String, Int>? = null

    val isReady: Boolean get() = table != null

    fun load(context: Context) {
        if (table != null) return
        val loaded = HashMap<String, Int>(40000)
        try {
            context.assets.open(ASSET_PATH).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val space = line.indexOf(' ')
                    if (space <= 0) return@forEach
                    val word = line.substring(0, space)
                    val value = line.substring(space + 1).toIntOrNull() ?: return@forEach
                    loaded[word] = value
                }
            }
            table = loaded
            Log.d(TAG, "word frequency loaded: ${loaded.size}")
        } catch (t: Throwable) {
            Log.w(TAG, "load word frequency failed: ${t.message}")
        }
    }

    /** 词频；词表里没有（或还没载入）返回 0 */
    fun freq(word: String): Int = table?.get(word) ?: 0
}
