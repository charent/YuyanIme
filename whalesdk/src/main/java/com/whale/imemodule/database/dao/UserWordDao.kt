package com.whale.imemodule.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.whale.imemodule.database.BaseDao
import com.whale.imemodule.database.entry.UserWord

@Dao
interface UserWordDao : BaseDao<UserWord> {

    /**
     * 列表分页：keyword 为空串时不按关键词过滤，source < 0 时不按来源过滤
     */
    @Query(
        """
        select * from user_word
        where (:keyword = '' or word like '%' || :keyword || '%' or code like '%' || :keyword || '%')
          and (:source < 0 or source = :source)
        order by weight desc, hits desc, updatedAt desc
        limit :limit offset :offset
        """
    )
    fun page(keyword: String, source: Int, limit: Int, offset: Int): List<UserWord>

    @Query(
        """
        select count(*) from user_word
        where (:keyword = '' or word like '%' || :keyword || '%' or code like '%' || :keyword || '%')
          and (:source < 0 or source = :source)
        """
    )
    fun countQuery(keyword: String, source: Int): Int

    @Query("select * from user_word where word = :word and code = :code")
    fun find(word: String, code: String): UserWord?

    @Query("select * from user_word order by weight desc, hits desc, updatedAt desc")
    fun getAll(): List<UserWord>

    @Query("select count(*) from user_word")
    fun count(): Int

    /** 权重最低、最久未用的词，用于超出上限时淘汰 */
    @Query("select * from user_word where source = 1 order by weight asc, hits asc, updatedAt asc limit :limit")
    fun weakestLearned(limit: Int): List<UserWord>

    @Query("delete from user_word where word = :word and code = :code")
    fun deleteByKey(word: String, code: String): Int

    @Query("delete from user_word where source = :source")
    fun deleteBySource(source: Int): Int

    @Query("delete from user_word")
    fun deleteAll()
}
