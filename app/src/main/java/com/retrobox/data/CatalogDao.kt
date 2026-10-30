package com.retrobox.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.retrobox.domain.Console
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Query("SELECT * FROM catalog ORDER BY title ASC")
    fun observeAll(): Flow<List<CatalogEntry>>

    @Query("SELECT * FROM catalog WHERE console = :console ORDER BY title ASC")
    fun observeByConsole(console: Console): Flow<List<CatalogEntry>>

    @Query("SELECT * FROM catalog WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CatalogEntry?

    @Query("SELECT * FROM catalog WHERE sha256 = :sha256 LIMIT 1")
    suspend fun getBySha256(sha256: String): CatalogEntry?

    @Query("SELECT COUNT(*) FROM catalog")
    suspend fun count(): Int

    @Query("SELECT MAX(catalogVersion) FROM catalog")
    suspend fun maxVersion(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<CatalogEntry>)

    @Query("DELETE FROM catalog WHERE id NOT IN (:ids)")
    suspend fun deleteExcept(ids: List<String>)

    @Query("DELETE FROM catalog")
    suspend fun clear()

    /**
     * Substitui o catálogo atomicamente. Se a lista vier vazia não apaga nada
     * (catálogo quebrado não deve zerar a loja).
     */
    @Transaction
    suspend fun replaceAll(entries: List<CatalogEntry>) {
        if (entries.isEmpty()) return
        deleteExcept(entries.map { it.id })
        insertAll(entries)
    }
}
