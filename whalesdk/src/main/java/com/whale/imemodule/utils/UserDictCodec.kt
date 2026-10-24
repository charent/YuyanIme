package com.whale.imemodule.utils

import com.whale.imemodule.database.entry.UserWord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用户词库的纯文本（TSV）编解码。
 *
 * 文件格式（UTF-8）：
 * ```
 * # whale-user-dict v1
 * # word<TAB>code<TAB>weight
 * 你好	nihao	0
 * ```
 * 以 # 开头的行与空行忽略；解析时依次尝试 TAB、逗号、全角逗号、空白作为分隔符。
 */
object UserDictCodec {

    const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
    const val MAX_IMPORT_LINES = 50000
    const val MAX_WORD_LENGTH = 20
    const val MAX_CODE_LENGTH = 24

    private const val HEADER_VERSION = "# whale-user-dict v1"
    private const val HEADER_FORMAT = "# word<TAB>code<TAB>weight"

    data class Entry(val word: String, val code: String, val weight: Int)

    class Parsed(val entries: List<Entry>, val failedLines: List<Int>)

    /**
     * 解析词库文本。不合法行记入 [Parsed.failedLines]（行号从 1 开始），不影响其余行。
     */
    fun parse(text: String): Parsed {
        val entries = LinkedHashMap<String, Entry>()
        val failed = mutableListOf<Int>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim().trimStart('\uFEFF')
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val fields = splitFields(line)
            if (fields.size < 2) {
                failed += index + 1
                return@forEachIndexed
            }
            val word = fields[0].trim()
            val code = normalizeCode(fields[1])
            if (word.isEmpty() || word.length > MAX_WORD_LENGTH ||
                code.isEmpty() || code.length > MAX_CODE_LENGTH
            ) {
                failed += index + 1
                return@forEachIndexed
            }
            val weight = fields.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
            entries["$word|$code"] = Entry(word, code, weight)
        }
        return Parsed(entries.values.toList(), failed)
    }

    fun serialize(words: List<UserWord>, timestamp: Long = System.currentTimeMillis()): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        return buildString(words.size * 24 + 128) {
            appendLine(HEADER_VERSION)
            appendLine(HEADER_FORMAT)
            appendLine("# exported at $time, ${words.size} entries")
            words.forEach {
                append(it.word).append('\t').append(it.code).append('\t').append(it.weight).append('\n')
            }
        }
    }

    fun normalizeCode(raw: String): String {
        val sb = StringBuilder(raw.length)
        raw.forEach { c ->
            when (c) {
                in 'a'..'z', in 'A'..'Z' -> sb.append(c.lowercaseChar())
                in '0'..'9' -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun splitFields(line: String): List<String> = when {
        line.contains('\t') -> line.split('\t')
        line.contains(',') -> line.split(',')
        line.contains('，') -> line.split('，')
        else -> line.split(Regex("\\s+"))
    }
}
