package com.whale.imemodule.manager

import com.whale.imemodule.application.CustomConstant
import com.whale.imemodule.database.DataBaseKT
import com.whale.imemodule.database.entry.UserWord
import com.whale.imemodule.prefs.AppPrefs
import com.whale.imemodule.utils.UserDictCodec
import com.whale.inputmethod.util.LX17PinYinUtils
import com.whale.inputmethod.util.T9PinYinUtils

/**
 * 用户词库的唯一读写入口。
 *
 * 为什么要有内存索引：`CustomEngine.processPhrase()` 在**每一次按键**都会被调用，
 * 而 Room 是 `allowMainThreadQueries()` 打开的；词库上万条时逐次查库会直接卡输入。
 * 因此启动时把启用中的词条全部索引进内存（code -> 前 8 条），查询零 IO，写操作同步维护索引。
 */
object UserDictManager {

    /** 词库软上限，超出后按「权重低、命中少、更久未用」淘汰自动学习的词 */
    const val MAX_WORDS = 20000

    /** 单次输入最多注入的候选数 */
    const val MAX_INJECT = 3

    const val MAX_CODE_LENGTH = 24
    private const val MAX_PER_CODE = 8
    private const val MIN_LEARN_WORD_LENGTH = 2
    private const val MAX_LEARN_WORD_LENGTH = 6
    private const val LEARN_DEBOUNCE_MS = 2000L

    private val lock = Any()
    private val index = HashMap<String, MutableList<UserWord>>()

    /** (word|code) -> 上次学习时间，防止连点重复落库 */
    private val recentLearned = HashMap<String, Long>()

    @Volatile
    private var loaded = false

    @Volatile
    private var total = 0

    private val dao get() = DataBaseKT.instance.userWordDao()

    enum class SaveResult { OK, EMPTY_WORD, INVALID_CODE, DUPLICATE }

    class ImportResult(val added: Int, val updated: Int, val skipped: Int, val failed: Int)

    // ---------------------------------------------------------------- 索引

    /**
     * 后台线程调用：把启用中的词条全部载入内存索引
     */
    fun load() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val rows = dao.getAll().filter { it.enabled }
            index.clear()
            rows.forEach { putLocked(it) }
            total = dao.count()
            loaded = true
        }
    }

    fun isLoaded(): Boolean = loaded

    fun reload() {
        synchronized(lock) {
            loaded = false
        }
        load()
    }

    /**
     * 编码到候选词的映射；同一编码下按 权重 > 命中次数 > 更新时间 排序
     */
    fun match(rawCode: String, limit: Int = MAX_INJECT): List<String> {
        if (!loaded) return emptyList()
        val code = UserDictCodec.normalizeCode(rawCode)
        if (code.isEmpty()) return emptyList()
        val words = synchronized(lock) { index[code] } ?: return emptyList()
        return words.asSequence().map { it.word }.distinct().take(limit).toList()
    }

    /**
     * 字母编码额外索引一份九键、乱序17 的按键串，让同一条词在多种方案下都能命中。
     * 含数字的编码（九键学习得到的）不做反向推导：T9 -> 拼音是多对多的，反推不可靠。
     */
    private fun aliasKeys(code: String): List<String> {
        if (code.isEmpty() || code.any { it in '0'..'9' }) return listOf(code)
        val t9 = buildString(code.length) { code.forEach { append(T9PinYinUtils.pinyin2T9Key(it)) } }
        val lx17 = buildString(code.length) { code.forEach { append(LX17PinYinUtils.pinyin2Lx17Key(it)) } }
        return listOf(code, t9, lx17).distinct()
    }

    private fun putLocked(word: UserWord) {
        aliasKeys(word.code).forEach { key ->
            val list = index.getOrPut(key) { mutableListOf() }
            list.removeAll { it.word == word.word && it.code == word.code }
            if (word.enabled) {
                list.add(word)
                list.sortWith(UserWord.RANK_COMPARATOR)
                if (list.size > MAX_PER_CODE) {
                    while (list.size > MAX_PER_CODE) list.removeAt(list.size - 1)
                }
            }
            if (list.isEmpty()) index.remove(key)
        }
    }

    private fun removeLocked(word: UserWord) {
        aliasKeys(word.code).forEach { key ->
            val list = index[key] ?: return@forEach
            list.removeAll { it.word == word.word && it.code == word.code }
            if (list.isEmpty()) index.remove(key)
        }
    }

    private fun rebuildIndexLocked() {
        index.clear()
        val rows = dao.getAll().filter { it.enabled }
        rows.forEach { putLocked(it) }
        total = dao.count()
    }

    // ---------------------------------------------------------------- 增删改查

    fun add(
        word: String,
        rawCode: String,
        weight: Int = 0,
        source: Int = UserWord.SOURCE_MANUAL,
        schema: String = ""
    ): SaveResult {
        val trimmed = word.trim()
        if (trimmed.isEmpty() || trimmed.length > UserDictCodec.MAX_WORD_LENGTH) return SaveResult.EMPTY_WORD
        val code = UserDictCodec.normalizeCode(rawCode)
        if (code.isEmpty() || code.length > MAX_CODE_LENGTH) return SaveResult.INVALID_CODE
        if (dao.find(trimmed, code) != null) return SaveResult.DUPLICATE

        val entity = UserWord(
            word = trimmed,
            code = code,
            schema = schema,
            source = source,
            hits = 1,
            weight = weight.coerceIn(0, 9999),
        )
        dao.insert(entity)
        synchronized(lock) {
            total = dao.count()
            putLocked(entity)
        }
        evictIfNeeded()
        return SaveResult.OK
    }

    /**
     * 修改词条。主键（word + code）变化时等价于「删旧 + 插新」；新主键已存在则判重。
     */
    fun update(
        oldWord: String,
        oldCode: String,
        word: String,
        rawCode: String,
        weight: Int
    ): SaveResult {
        val trimmed = word.trim()
        if (trimmed.isEmpty() || trimmed.length > UserDictCodec.MAX_WORD_LENGTH) return SaveResult.EMPTY_WORD
        val code = UserDictCodec.normalizeCode(rawCode)
        if (code.isEmpty() || code.length > MAX_CODE_LENGTH) return SaveResult.INVALID_CODE

        val old = dao.find(oldWord, oldCode) ?: return SaveResult.EMPTY_WORD
        val keyChanged = old.word != trimmed || old.code != code
        if (keyChanged && dao.find(trimmed, code) != null) return SaveResult.DUPLICATE

        val updated = old.copy(
            word = trimmed,
            code = code,
            weight = weight.coerceIn(0, 9999),
            // 用户确认过的词不再算「学习词」，避免被自动淘汰
            source = UserWord.SOURCE_MANUAL,
            updatedAt = System.currentTimeMillis(),
        )
        dao.deleteByKey(old.word, old.code)
        dao.insert(updated)
        synchronized(lock) {
            removeLocked(old)
            putLocked(updated)
            total = dao.count()
        }
        return SaveResult.OK
    }

    fun delete(word: String, code: String) {
        val old = dao.find(word, code) ?: return
        dao.deleteByKey(word, code)
        synchronized(lock) {
            removeLocked(old)
            total = dao.count()
        }
    }

    fun clearAll() {
        dao.deleteAll()
        synchronized(lock) {
            index.clear()
            total = 0
        }
    }

    fun clearLearned() {
        dao.deleteBySource(UserWord.SOURCE_LEARNED)
        synchronized(lock) { rebuildIndexLocked() }
    }

    fun count(): Int = total

    fun count(keyword: String, source: Int): Int = dao.countQuery(keyword, source)

    fun page(keyword: String, source: Int, limit: Int, offset: Int): List<UserWord> =
        dao.page(keyword, source, limit, offset)

    fun all(): List<UserWord> = dao.getAll()

    // ---------------------------------------------------------------- 自动学习

    /**
     * 记录一次「用户纠正」：输入 code 后选择了 [word]。
     *
     * @param rawCode 本次输入开始时完整的组合串（如 `nihao`、`64426`、双拼的 `nh`）
     * @param word    用户最终选中的词
     * @param schema  当前 Rime 方案 id
     */
    fun learn(rawCode: String, word: String, schema: String) {
        if (!AppPrefs.getInstance().input.userDictAutoLearn.getValue()) return

        val code = UserDictCodec.normalizeCode(rawCode)
        val trimmed = word.trim()
        if (!isLearnable(trimmed, code)) return
        if (schema == CustomConstant.SCHEMA_EN || schema == CustomConstant.SCHEMA_ZH_HANDWRITING) return

        val now = System.currentTimeMillis()
        val recentKey = "$trimmed|$code"
        synchronized(lock) {
            val last = recentLearned[recentKey]
            if (last != null && now - last < LEARN_DEBOUNCE_MS) return
            recentLearned[recentKey] = now
            if (recentLearned.size > 512) recentLearned.clear()
        }

        val existing = dao.find(trimmed, code)
        if (existing != null) {
            existing.hits += 1
            existing.updatedAt = now
            dao.update(existing)
            synchronized(lock) { putLocked(existing) }
            return
        }

        val entity = UserWord(
            word = trimmed,
            code = code,
            schema = schema,
            source = UserWord.SOURCE_LEARNED,
            hits = 1,
            weight = 0,
        )
        dao.insert(entity)
        synchronized(lock) {
            total = dao.count()
            putLocked(entity)
        }
        evictIfNeeded()
    }

    private fun isLearnable(word: String, code: String): Boolean {
        if (word.isEmpty() || word.length > MAX_LEARN_WORD_LENGTH) return false
        if (code.isEmpty() || code.length > MAX_CODE_LENGTH) return false
        if (word == code) return false
        if (word.any { it.isWhitespace() || it.isISOControl() }) return false
        val cjk = word.count { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' }
        return cjk >= MIN_LEARN_WORD_LENGTH
    }

    private fun evictIfNeeded() {
        synchronized(lock) {
            if (total <= MAX_WORDS) return
            val extra = total - MAX_WORDS
            val weakest = dao.weakestLearned(extra)
            weakest.forEach {
                dao.deleteByKey(it.word, it.code)
                removeLocked(it)
            }
            total = dao.count()
        }
    }

    // ---------------------------------------------------------------- 导入导出

    fun parse(text: String): UserDictCodec.Parsed = UserDictCodec.parse(text)

    private fun existingMap(): HashMap<String, UserWord> {
        val map = HashMap<String, UserWord>()
        dao.getAll().forEach { map["${it.word}|${it.code}"] = it }
        return map
    }

    /**
     * 导入预检：统计新增 / 已存在 / 非法行，供二次确认
     */
    fun previewImport(parsed: UserDictCodec.Parsed): ImportResult {
        val existing = existingMap()
        var added = 0
        var exist = 0
        parsed.entries.forEach {
            if (existing.containsKey("${it.word}|${it.code}")) exist++ else added++
        }
        return ImportResult(added, exist, 0, parsed.failedLines.size)
    }

    /**
     * 执行导入。[overwrite] = true 时用文件里的权重覆盖已存在词条，否则只累加命中次数。
     */
    fun applyImport(parsed: UserDictCodec.Parsed, overwrite: Boolean): ImportResult {
        var added = 0
        var updated = 0
        var skipped = 0
        val now = System.currentTimeMillis()
        val existing = existingMap()
        DataBaseKT.instance.runInTransaction {
            parsed.entries.forEach { entry ->
                val key = "${entry.word}|${entry.code}"
                val old = existing[key]
                if (old == null) {
                    dao.insert(
                        UserWord(
                            word = entry.word,
                            code = entry.code,
                            source = UserWord.SOURCE_MANUAL,
                            hits = 1,
                            weight = entry.weight.coerceIn(0, 9999),
                            createdAt = now,
                            updatedAt = now,
                        )
                    )
                    added++
                } else if (overwrite) {
                    old.weight = entry.weight.coerceIn(0, 9999)
                    old.updatedAt = now
                    dao.update(old)
                    updated++
                } else {
                    old.hits += 1
                    dao.update(old)
                    skipped++
                }
            }
        }
        synchronized(lock) { rebuildIndexLocked() }
        evictIfNeeded()
        return ImportResult(added, updated, skipped, parsed.failedLines.size)
    }

    fun exportText(): String = UserDictCodec.serialize(dao.getAll())
}
