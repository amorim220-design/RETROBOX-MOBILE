package com.retrobox.data

import androidx.room.*
import com.retrobox.domain.Console
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM games ORDER BY title ASC")
    fun getAllGames(): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE console = :console ORDER BY title ASC")
    fun getGamesByConsole(console: Console): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavoriteGames(): Flow<List<Game>>

    @Query("SELECT * FROM games ORDER BY lastPlayed DESC LIMIT 10")
    fun getRecentGames(): Flow<List<Game>>

    @Query("SELECT * FROM games ORDER BY dateAdded DESC")
    fun getRecentlyAdded(): Flow<List<Game>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGame(game: Game): Long

    @Update
    suspend fun updateGame(game: Game)

    @Delete
    suspend fun deleteGame(game: Game)

    @Query("SELECT * FROM games WHERE filePath = :filePath LIMIT 1")
    suspend fun getGameByPath(filePath: String): Game?

    @Query("SELECT * FROM games WHERE catalogId = :catalogId LIMIT 1")
    suspend fun getGameByCatalogId(catalogId: String): Game?

    @Query("SELECT * FROM games WHERE sha256 = :sha256 LIMIT 1")
    suspend fun getGameBySha256(sha256: String): Game?

    @Query("SELECT * FROM games WHERE LOWER(title) = LOWER(:title) LIMIT 1")
    suspend fun getGameByTitle(title: String): Game?

    @Query("SELECT * FROM games")
    suspend fun getAllNow(): List<Game>

    @Query("SELECT COUNT(*) FROM games")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM games")
    suspend fun clear()
}
