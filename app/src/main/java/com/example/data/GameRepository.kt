package com.example.data

import com.example.domain.Console
import kotlinx.coroutines.flow.Flow

class GameRepository(private val gameDao: GameDao) {
    val allGames: Flow<List<Game>> = gameDao.getAllGames()
    val favoriteGames: Flow<List<Game>> = gameDao.getFavoriteGames()
    val recentGames: Flow<List<Game>> = gameDao.getRecentGames()

    fun getGamesByConsole(console: Console): Flow<List<Game>> = gameDao.getGamesByConsole(console)

    suspend fun addGame(game: Game) {
        if (gameDao.getGameByPath(game.filePath) == null) {
            gameDao.insertGame(game)
        }
    }

    suspend fun updateGame(game: Game) = gameDao.updateGame(game)
    suspend fun deleteGame(game: Game) = gameDao.deleteGame(game)
}
