package com.retrobox.data

import com.retrobox.domain.Console
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

    /** Atualiza a capa de um jogo ja cadastrado. */
    suspend fun updateCover(filePath: String, coverUri: String) {
        gameDao.getGameByPath(filePath)?.let { game ->
            gameDao.updateGame(game.copy(coverUri = coverUri))
        }
    }
}
