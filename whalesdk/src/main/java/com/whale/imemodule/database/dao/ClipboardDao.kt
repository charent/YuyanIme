package com.whale.imemodule.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.whale.imemodule.database.BaseDao
import com.whale.imemodule.database.entry.Clipboard

@Dao
interface ClipboardDao : BaseDao<Clipboard> {

    @Query("select * from clipboard ORDER BY isKeep DESC, time DESC")
    fun getAll(): List<Clipboard>

    /**
     * 分页查询：剪贴板最多保留 1000 条、单条正文最长 65535 字符，
     * 列表界面按页加载，避免一次性把全部正文读进内存。
     */
    @Query("select * from clipboard ORDER BY isKeep DESC, time DESC LIMIT :limit OFFSET :offset")
    fun getPage(limit: Int, offset: Int): List<Clipboard>

    @Query("delete from clipboard where content = :content")
    fun deleteByContent(content: String)

    @Query("delete from clipboard")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM clipboard")
    fun getCount(): Int

    @Query("DELETE FROM clipboard WHERE content IN ( SELECT content FROM clipboard ORDER BY time ASC LIMIT :overflow)")
    fun deleteOldest(overflow: Int)

    @Query("DELETE FROM clipboard WHERE isKeep = 0")
    fun deleteAllExceptKeep()
}
