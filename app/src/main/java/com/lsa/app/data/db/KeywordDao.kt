package com.lsa.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The user's own search keywords ("My keywords"), see [UserKeywordEntity]. */
@Dao
interface KeywordDao {
    @Query("SELECT * FROM user_keywords WHERE sourceKey = :sourceKey ORDER BY createdAt, id")
    fun observe(sourceKey: String): Flow<List<UserKeywordEntity>>

    @Query("SELECT * FROM user_keywords WHERE sourceKey = :sourceKey ORDER BY createdAt, id")
    suspend fun of(sourceKey: String): List<UserKeywordEntity>

    @Query("SELECT * FROM user_keywords")
    suspend fun all(): List<UserKeywordEntity>

    /** Files that have at least one keyword (the 🏷 mark in the index grid). */
    @Query("SELECT DISTINCT sourceKey FROM user_keywords")
    fun observeKeys(): Flow<List<String>>

    @Query("SELECT DISTINCT sourceKey FROM user_keywords")
    suspend fun keys(): List<String>

    /** Ignores a keyword the file already has. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<UserKeywordEntity>): List<Long>

    @Query("DELETE FROM user_keywords WHERE sourceKey = :sourceKey AND text = :text")
    suspend fun delete(sourceKey: String, text: String)

    @Query("DELETE FROM user_keywords WHERE sourceKey IN (:keys)")
    suspend fun deleteFor(keys: List<String>)

    /** The file moved and got a new identity; its keywords follow it. */
    @Query("UPDATE OR IGNORE user_keywords SET sourceKey = :to WHERE sourceKey = :from")
    suspend fun rekey(from: String, to: String)
}
