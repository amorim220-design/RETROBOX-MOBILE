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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGame(game: Game)

    @Update
    suspend fun updateGame(game: Game)

    @Delete
    suspend fun deleteGame(game: Game)

    @Query("SELECT * FROM games WHERE filePath = :filePath LIMIT 1")
    suspend fun getGameByPath(filePath: String): Game?
}
