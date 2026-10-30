package com.retrobox.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads")
    fun observeAll(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads WHERE catalogId = :catalogId LIMIT 1")
    suspend fun get(catalogId: String): DownloadRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: DownloadRecord)

    @Query("DELETE FROM downloads WHERE catalogId = :catalogId")
    suspend fun delete(catalogId: String)

    @Query("DELETE FROM downloads WHERE state IN ('COMPLETED', 'CANCELED')")
    suspend fun clearFinished()
}
