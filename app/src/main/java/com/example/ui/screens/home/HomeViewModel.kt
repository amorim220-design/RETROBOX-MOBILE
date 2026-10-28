package com.example.ui.screens.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Game
import com.example.data.GameRepository
import com.example.data.UserPreferencesRepository
import com.example.domain.Console
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

    val games: StateFlow<List<Game>> = combine(
        selectedConsole,
        searchQuery,
        gameRepository.allGames
    ) { console, query, allGames ->
        allGames.filter { it.console == console && it.title.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentGames: StateFlow<List<Game>> = gameRepository.recentGames
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
            val game = Game(
                title = fileName.substringBeforeLast("."),
                console = console,
                filePath = uri.toString(),
                coverUri = null // In a real app, we might fetch this
            )
            gameRepository.addGame(game)
        }
    }

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
