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

    /**
     * Busca por título (e console opcional). Usada na tela do catálogo.
     *
     * Usa `instr(lower(...))` em vez de `LIKE`: sem curingas, sem ESCAPE e
     * insensível a maiúsculas.
     */
    @Query(
        "SELECT * FROM catalog WHERE instr(lower(title), lower(:query)) > 0 " +
            "AND (:console IS NULL OR console = :console) ORDER BY title ASC LIMIT 400"
    )
    fun search(query: String, console: Console?): Flow<List<CatalogEntry>>

    /** Consolas presentes no catálogo (para os chips de filtro). */
    @Query("SELECT DISTINCT console FROM catalog")
    fun observeConsoles(): Flow<List<Console>>

    @Query("SELECT * FROM catalog WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CatalogEntry?

    @Query("SELECT * FROM catalog WHERE md5 = :md5 LIMIT 1")
    suspend fun getByMd5(md5: String): CatalogEntry?

    @Query("SELECT * FROM catalog WHERE sha256 = :sha256 LIMIT 1")
    suspend fun getBySha256(sha256: String): CatalogEntry?

    @Query("SELECT COUNT(*) FROM catalog")
    suspend fun count(): Int

    @Query("SELECT MAX(catalogVersion) FROM catalog")
    suspend fun maxVersion(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<CatalogEntry>)

    @Query("DELETE FROM catalog")
    suspend fun clear()

    /**
     * Substitui o catálogo atomicamente.
     *
     * Não usamos `DELETE ... WHERE id NOT IN (:ids)`: com dezenas de milhares de
     * itens o SQLite estoura o limite de variáveis por comando. Como a troca
     * roda dentro de uma transação, a lista antiga só some quando a nova
     * estiver completa.
     */
    @Transaction
    suspend fun replaceAll(entries: List<CatalogEntry>) {
        if (entries.isEmpty()) return
        clear()
        insertAll(entries)
    }
}
