package com.retrobox.data

import com.retrobox.domain.Console
import kotlinx.coroutines.flow.Flow

class GameRepository(
    private val gameDao: GameDao,
    private val catalogDao: CatalogDao,
    private val downloadDao: DownloadDao
) {
    val allGames: Flow<List<Game>> = gameDao.getAllGames()
    val favoriteGames: Flow<List<Game>> = gameDao.getFavoriteGames()
    val recentGames: Flow<List<Game>> = gameDao.getRecentGames()
    val recentlyAdded: Flow<List<Game>> = gameDao.getRecentlyAdded()
    val gameCount: Flow<Int> = gameDao.observeCount()

    val catalog: Flow<List<CatalogEntry>> = catalogDao.observeAll()
    val downloads: Flow<List<DownloadRecord>> = downloadDao.observeAll()

    fun getGamesByConsole(console: Console): Flow<List<Game>> = gameDao.getGamesByConsole(console)

    fun getCatalogByConsole(console: Console): Flow<List<CatalogEntry>> =
        catalogDao.observeByConsole(console)

    /** Busca no catálogo por título (com console opcional). */
    fun searchCatalog(query: String, console: Console?): Flow<List<CatalogEntry>> =
        catalogDao.search(query.trim(), console)

    /** Consolas que existem no catálogo (chips de filtro). */
    val catalogConsoles: Flow<List<Console>> = catalogDao.observeConsoles()

    /**
     * Adiciona um jogo importado. Devolve `false` se já existia um arquivo no
     * mesmo caminho; se o mesmo conteúdo (sha256) já está na biblioteca, também
     * não duplica.
     */
    suspend fun addGame(game: Game): Boolean {
        if (gameDao.getGameByPath(game.filePath) != null) return false
        if (!game.sha256.isNullOrBlank() && gameDao.getGameBySha256(game.sha256) != null) return false
        return gameDao.insertGame(game) > 0
    }

    /** Cria o jogo a partir de um item do catálogo já baixado/validado. */
    suspend fun addDownloaded(
        entry: CatalogEntry,
        filePath: String,
        title: String,
        coverUri: String?
    ): Boolean {
        if (gameDao.getGameByCatalogId(entry.id) != null) return false
        val game = Game(
            title = title,
            console = entry.console,
            filePath = filePath,
            coverUri = coverUri ?: entry.coverUrl,
            rawTitle = entry.fileName,
            catalogId = entry.id,
            sha256 = entry.sha256
        )
        return gameDao.insertGame(game) > 0
    }

    suspend fun updateGame(game: Game) = gameDao.updateGame(game)

    suspend fun deleteGame(game: Game) = gameDao.deleteGame(game)

    /** Atualiza a capa de um jogo ja cadastrado. */
    suspend fun updateCover(filePath: String, coverUri: String) {
        gameDao.getGameByPath(filePath)?.let { game ->
            gameDao.updateGame(game.copy(coverUri = coverUri))
        }
    }

    suspend fun gameByPath(filePath: String): Game? = gameDao.getGameByPath(filePath)

    suspend fun gameByCatalogId(catalogId: String): Game? = gameDao.getGameByCatalogId(catalogId)

    suspend fun gameBySha256(sha256: String): Game? = gameDao.getGameBySha256(sha256)

    suspend fun allGamesNow(): List<Game> = gameDao.getAllNow()

    // ------------------------------------------------------------- catálogo --

    suspend fun replaceCatalog(entries: List<CatalogEntry>) = catalogDao.replaceAll(entries)

    suspend fun catalogCount(): Int = catalogDao.count()

    suspend fun catalogVersion(): Int = catalogDao.maxVersion() ?: 0

    suspend fun catalogEntry(id: String): CatalogEntry? = catalogDao.getById(id)

    suspend fun catalogEntryBySha256(sha256: String): CatalogEntry? = catalogDao.getBySha256(sha256)

    /** Casa pelo MD5 do cartucho (identidade usada pela base do libretro). */
    suspend fun catalogEntryByMd5(md5: String): CatalogEntry? = catalogDao.getByMd5(md5)

    /** Remove o catálogo e a biblioteca (não apaga arquivos: isso é feito fora). */
    suspend fun clearAll() {
        catalogDao.clear()
        gameDao.clear()
    }

    // ------------------------------------------------------------ downloads --

    suspend fun downloadRecord(catalogId: String): DownloadRecord? = downloadDao.get(catalogId)

    suspend fun setDownload(record: DownloadRecord) = downloadDao.upsert(record)

    suspend fun deleteDownload(catalogId: String) = downloadDao.delete(catalogId)

    suspend fun clearFinishedDownloads() = downloadDao.clearFinished()
}
