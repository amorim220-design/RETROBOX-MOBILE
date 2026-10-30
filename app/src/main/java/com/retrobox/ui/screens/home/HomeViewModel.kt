package com.retrobox.ui.screens.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.retrobox.data.Game
import com.retrobox.data.GameRepository
import com.retrobox.data.UserPreferencesRepository
import com.retrobox.domain.Console
import com.retrobox.emulation.CoverStore
import com.retrobox.emulation.GameTitle
import com.retrobox.emulation.LibraryImporter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HomeViewModel(
    private val gameRepository: GameRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _selectedConsole = MutableStateFlow(Console.PS1)
    val selectedConsole: StateFlow<Console> = _selectedConsole.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * Biblioteca: só entra o que veio do catálogo (baixado ou "use seu arquivo").
     * Arquivos soltos na pasta do app que não batem com o catálogo ficam de fora.
     */
    private val library = gameRepository.allGames
        .map { list -> list.filter { it.catalogId != null } }

    val games: StateFlow<List<Game>> = combine(
        selectedConsole,
        searchQuery,
        library
    ) { console, query, allGames ->
        allGames.filter { it.console == console && it.title.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentGames: StateFlow<List<Game>> = gameRepository.recentGames
        .map { list -> list.filter { it.catalogId != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allGames: StateFlow<List<Game>> = library
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteGames: StateFlow<List<Game>> = gameRepository.favoriteGames
        .map { list -> list.filter { it.catalogId != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            userPreferencesRepository.lastSelectedConsole.collect { console ->
                console?.let { _selectedConsole.value = it }
            }
        }
    }

    fun selectConsole(console: Console) {
        _selectedConsole.value = console
        viewModelScope.launch {
            userPreferencesRepository.updateLastConsole(console)
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun importGame(context: Context, uri: Uri, console: Console) {
        viewModelScope.launch {
            val fileName = getFileName(context, uri) ?: "Unknown Game"
            val title = GameTitle.clean(fileName)
            val detected = Console.fromFileName(fileName) ?: console
            val game = Game(
                title = title,
                console = detected,
                filePath = uri.toString(),
                coverUri = null,
                rawTitle = fileName
            )
            gameRepository.addGame(game)
            _selectedConsole.value = detected
            userPreferencesRepository.updateLastConsole(detected)
            _message.emit("$title adicionado em ${detected.displayName}")

            // tenta buscar a capa na internet (silencioso se nao houver rede)
            CoverStore.coverFor(context, detected, fileName)?.let { cover ->
                gameRepository.updateCover(uri.toString(), cover)
            }
        }
    }

    /**
     * Importa uma pasta inteira (uma subpasta por console) para a biblioteca.
     */
    fun importFolder(context: Context, treeUri: Uri) {
        if (isImporting) return
        isImporting = true
        viewModelScope.launch {
            _message.emit("Importando jogos…")
            val result = LibraryImporter.importTree(
                context = context,
                treeUri = treeUri,
                repository = gameRepository
            ) { current, total, label ->
                _message.tryEmit("Importando $current/$total — $label")
            }
            isImporting = false

            val summary = buildString {
                append("${result.imported} jogo(s) importado(s)")
                if (result.withCover > 0) append(", ${result.withCover} com capa")
                if (result.skipped > 0) append(", ${result.skipped} ignorado(s)")
            }
            _message.emit(summary)
            result.errors.take(3).forEach { _message.emit(it) }
        }
    }

    private var isImporting = false

    /**
     * Importa da pasta /Android/data/<app>/files/JOGOS do proprio aparelho.
     * Nao precisa de permissao nem do seletor de arquivos: e o jeito mais
     * simples de colocar as ROMs no celular (via USB ou explorador de arquivos).
     */
    fun importFromAppFolder(context: Context) {
        if (isImporting) return
        isImporting = true
        viewModelScope.launch {
            val result = LibraryImporter.importAppFolder(context, gameRepository) { current, total, label ->
                _message.tryEmit("Importando $current/$total — $label")
            }
            isImporting = false
            if (result.imported > 0) {
                _message.emit("${result.imported} jogo(s) da pasta do app" +
                    if (result.withCover > 0) ", ${result.withCover} com capa" else "")
            }
        }
    }

    /**
     * Busca as capas que faltam (jogos importados antes, ou que falharam por
     * falta de rede). Só uma vez por sessão.
     */
    fun refreshMissingCovers(context: Context) {
        if (coversRefreshed) return
        coversRefreshed = true
        viewModelScope.launch {
            val pending = gameRepository.allGames.first()
                .filter { it.coverUri == null && !it.rawTitle.isNullOrBlank() }
            if (pending.isEmpty()) return@launch
            pending.forEach { game ->
                val cover = CoverStore.coverFor(context, game.console, game.rawTitle!!)
                if (cover != null) gameRepository.updateCover(game.filePath, cover)
            }
        }
    }

    private var coversRefreshed = false

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val message: SharedFlow<String> = _message

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    fun toggleFavorite(game: Game) {
        viewModelScope.launch {
            gameRepository.updateGame(game.copy(isFavorite = !game.isFavorite))
        }
    }

    /**
     * Remove o jogo da biblioteca. Com [deleteFile] = true apaga também a cópia
     * dentro do app (`files/roms/...`); o arquivo original do usuário, que fica
     * fora do app, nunca é tocado.
     */
    fun deleteGame(game: Game, deleteFile: Boolean) {
        viewModelScope.launch {
            gameRepository.deleteGame(game)
            if (deleteFile && game.filePath.startsWith("/")) {
                val removed = runCatching { java.io.File(game.filePath).delete() }.getOrDefault(false)
                _message.emit(
                    if (removed) "“${game.title}” removido (arquivo apagado)"
                    else "“${game.title}” removido da lista"
                )
            } else {
                _message.emit("“${game.title}” removido da lista")
            }
        }
    }

    fun launchGame(game: Game) {
        viewModelScope.launch {
            gameRepository.updateGame(game.copy(lastPlayed = System.currentTimeMillis()))
            // Emulation logic would go here
        }
    }
}

class HomeViewModelFactory(
    private val gameRepository: GameRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(gameRepository, userPreferencesRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
