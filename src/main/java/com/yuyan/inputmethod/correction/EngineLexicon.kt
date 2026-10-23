package com.yuyan.inputmethod.correction

import java.io.File
import java.io.RandomAccessFile

/**
 * 只读访问引擎自己的词库 `rime/build/pinyin.table.bin`。
 *
 * ## 为什么直接读它
 *
 * 纠错要回答的问题只有一个：「这个编码像不像一个真实存在的词」。此前用 jieba 词表 +
 * 二元搭配统计去近似，覆盖不到「烂大街」「明矾」这类词，只能不断加阈值和补丁。
 * 而这个文件正是引擎用来出候选的词典：191 万条词条、带 librime 权重，
 * 「比好」「辣死公」这类音节硬拼的组合**不在里面**，天然被过滤掉。
 *
 * ## 格式（librime `dict/table.{h,cc}`，`Rime::Table/4.0`）
 *
 * mapped file，所有指针都是**相对自身地址**的 int32 偏移：
 * `OffsetPtr::get() = (char*)&offset_ + offset_`。
 * 只需要三块：
 * - Metadata：`format[32]` 之后是 checksum / num_syllables / num_entries，
 *   第 44、48 字节起是 syllabary 与 index 的相对偏移；
 * - index（`HeadIndex = Array<HeadIndexNode>`）：用第 1 个音节的 SyllableId 直接下标；
 * - 之后逐层 `TrunkIndex`（按 key 升序，二分），第 4 个音节起是 `TailIndex`（`LongEntry`）。
 *
 * **不碰 string table（marisa-trie）**：判断只要 `Entry.weight`，
 * 候选文本仍由引擎给出（影子查询只查最终 1 个编码）。
 *
 * 任何一步不符合预期，[open] 返回 null，纠错退回「不提示」，绝不猜。
 */
class EngineLexicon private constructor(
    private val file: RandomAccessFile,
    private val numSyllables: Int,
    private val indexAt: Long,
    /** 词条总数，仅用于诊断日志 */
    val numEntries: Int,
) : AutoCloseable {

    private val scratch = ByteArray(4)

    /**
     * 该编码下所有词条里的最高权重；编码不在词库里返回 0。
     *
     * @param ids 已切好并映射为 `SyllableId` 的音节序列
     */
    fun maxWeight(ids: IntArray): Float {
        if (ids.isEmpty() || ids.size > MAX_SYLLABLES) return 0f
        val first = ids[0]
        if (first < 0 || first >= numSyllables) return 0f

        val headNode = indexAt + ARRAY_HEADER + first * HEAD_NODE_SIZE
        if (ids.size == 1) return maxEntryWeight(headNode)

        val nextRel = readInt(headNode + HEAD_NEXT_LEVEL)
        if (nextRel == 0) return 0f
        var arrayAt = headNode + HEAD_NEXT_LEVEL + nextRel

        var level = 1
        while (level < ids.size) {
            if (level <= 2) {
                val node = findTrunkNode(arrayAt, ids[level]) ?: return 0f
                if (level == ids.size - 1) return maxEntryWeight(node + TRUNK_ENTRIES)
                val rel = readInt(node + TRUNK_NEXT_LEVEL)
                if (rel == 0) return 0f
                arrayAt = node + TRUNK_NEXT_LEVEL + rel
            } else {
                return tailMaxWeight(arrayAt, ids, level)
            }
            level++
        }
        return 0f
    }

    override fun close() {
        try { file.close() } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------- 结构遍历

    /** 在 `TrunkIndex = Array<TrunkIndexNode>` 里二分 key，返回节点绝对偏移 */
    private fun findTrunkNode(arrayAt: Long, key: Int): Long? {
        val size = readInt(arrayAt)
        if (size <= 0 || size > MAX_TRUNK_NODES) return null
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val node = arrayAt + ARRAY_HEADER + mid.toLong() * TRUNK_NODE_SIZE
            val k = readInt(node)
            when {
                k == key -> return node
                k < key -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return null
    }

    /** `TailIndex = Array<LongEntry>`：取 `extra_code` 匹配的最高权重 */
    private fun tailMaxWeight(arrayAt: Long, ids: IntArray, extraFrom: Int): Float {
        val size = readInt(arrayAt)
        if (size <= 0 || size > MAX_TAIL_ENTRIES) return 0f
        var best = 0f
        for (k in 0 until size) {
            val node = arrayAt + ARRAY_HEADER + k.toLong() * LONG_ENTRY_SIZE
            val w = readFloat(node + LONG_ENTRY_WEIGHT)
            if (w <= best) continue
            if (!extraCodeMatches(node, ids, extraFrom)) continue
            best = w
        }
        return best
    }

    private fun extraCodeMatches(node: Long, ids: IntArray, extraFrom: Int): Boolean {
        val want = ids.size - extraFrom
        if (want == 0) return true
        if (readInt(node + LONG_ENTRY_CODE_SIZE) != want) return false
        val rel = readInt(node + LONG_ENTRY_CODE_AT)
        if (rel == 0) return false
        val at = node + LONG_ENTRY_CODE_AT + rel
        for (i in 0 until want) {
            if (readInt(at + i * 4L) != ids[extraFrom + i]) return false
        }
        return true
    }

    /** `List<Entry>` 的最高权重；[listAt] 指向 List 的 size 字段 */
    private fun maxEntryWeight(listAt: Long): Float {
        val size = readInt(listAt)
        if (size <= 0 || size > MAX_ENTRIES_PER_LIST) return 0f
        val rel = readInt(listAt + 4)
        if (rel == 0) return 0f
        val entriesAt = listAt + 4 + rel
        var best = 0f
        for (i in 0 until size) {
            val w = readFloat(entriesAt + i * ENTRY_SIZE.toLong() + ENTRY_WEIGHT)
            if (w > best) best = w
        }
        return best
    }

    // ---------------------------------------------------------------- 读取原语（小端）

    private fun readInt(at: Long): Int {
        file.seek(at)
        file.readFully(scratch, 0, 4)
        return (scratch[0].toInt() and 0xFF) or
            ((scratch[1].toInt() and 0xFF) shl 8) or
            ((scratch[2].toInt() and 0xFF) shl 16) or
            ((scratch[3].toInt() and 0xFF) shl 24)
    }

    private fun readFloat(at: Long): Float = java.lang.Float.intBitsToFloat(readInt(at))

    companion object {
        private const val FORMAT_PREFIX = "Rime::Table/"
        private const val OFF_NUM_ENTRIES = 40
        private const val OFF_NUM_SYLLABLES = 36
        private const val OFF_INDEX = 48

        private const val ARRAY_HEADER = 4L
        private const val HEAD_NODE_SIZE = 12L
        private const val HEAD_NEXT_LEVEL = 8L
        private const val TRUNK_NODE_SIZE = 16L
        private const val TRUNK_ENTRIES = 4L
        private const val TRUNK_NEXT_LEVEL = 12L
        private const val LONG_ENTRY_SIZE = 16L
        private const val LONG_ENTRY_CODE_SIZE = 0L
        private const val LONG_ENTRY_CODE_AT = 4L
        private const val LONG_ENTRY_WEIGHT = 12L
        private const val ENTRY_SIZE = 8
        private const val ENTRY_WEIGHT = 4L

        private const val MAX_SYLLABLES = 12
        private const val MAX_TRUNK_NODES = 100_000
        private const val MAX_TAIL_ENTRIES = 200_000
        private const val MAX_ENTRIES_PER_LIST = 5000

        fun open(path: String): EngineLexicon? = open(File(path))

        /**
         * 进程级句柄。词库是只读的，整个进程开一份就够；[Launcher] 在子线程里准备好后，
         * 纠错直接取用。没准备好（或文件不可解析）时返回 null，纠错整体不提示。
         */
        @Volatile
        private var shared: EngineLexicon? = null

        /** 由 `Launcher` 在初始化时调用；失败不抛异常，只是后续纠错静默关闭 */
        fun prepare(path: String) {
            val opened = open(path)
            shared = opened
            if (opened == null) {
                android.util.Log.w("PinyinCorrection", "engine lexicon unavailable: $path")
            } else {
                android.util.Log.i(
                    "PinyinCorrection",
                    "engine lexicon ready: ${opened.numEntries} entries, " +
                        "${opened.numSyllables} syllables",
                )
            }
        }

        fun shared(): EngineLexicon? = shared

        fun open(file: File): EngineLexicon? {
            if (!file.isFile || file.length() < 4096) return null
            var raf: RandomAccessFile? = null
            return try {
                raf = RandomAccessFile(file, "r")
                val header = ByteArray(64)
                raf.readFully(header)
                val format = String(header, 0, 32, Charsets.US_ASCII).trimEnd('\u0000')
                if (!format.startsWith(FORMAT_PREFIX)) return null

                val numSyllables = leInt(header, OFF_NUM_SYLLABLES)
                if (numSyllables <= 0 || numSyllables > 4096) return null
                val indexAt = OFF_INDEX + leInt(header, OFF_INDEX).toLong()
                if (indexAt < 64 || indexAt >= file.length()) return null

                EngineLexicon(raf, numSyllables, indexAt, leInt(header, OFF_NUM_ENTRIES))
            } catch (t: Throwable) {
                try { raf?.close() } catch (_: Throwable) {}
                null
            }
        }

        private fun leInt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}
