package com.retrobox.ui.screens.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.retrobox.RetroBoxApplication
import com.retrobox.backup.SaveBackupManager
import com.retrobox.data.StorageLayout
import com.retrobox.emulation.BiosManager
import com.retrobox.emulation.ErrorReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class StorageStats(
    val romsBytes: Long = 0,
    val savesBytes: Long = 0,
    val coversBytes: Long = 0,
    val games: Int = 0,
    val catalog: Int = 0
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RetroBoxApplication
    private val preferences = app.userPreferencesRepository
    private val repository = app.gameRepository
    private val catalog = app.catalogRepository

    val lastRefresh = preferences.catalogLastRefresh
    val lastVersion = preferences.catalogLastVersion
    val catalogError = preferences.catalogLastError

    private val _catalogUrl = MutableStateFlow("")
    val catalogUrl: StateFlow<String> = _catalogUrl.asStateFlow()

    private val _stats = MutableStateFlow(StorageStats())
    val stats: StateFlow<StorageStats> = _stats.asStateFlow()

    private val _backups = MutableStateFlow<List<File>>(emptyList())
    val backups: StateFlow<List<File>> = _backups.asStateFlow()

    private val _bios = MutableStateFlow<List<BiosManager.Status>>(emptyList())
    val bios: StateFlow<List<BiosManager.Status>> = _bios.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val message: SharedFlow<String> = _message

    init {
        viewModelScope.launch {
            _catalogUrl.value = preferences.catalogUrl.first()
            refreshAll()
        }
    }

    fun updateCatalogUrl(value: String) {
        _catalogUrl.value = value
    }

    fun saveCatalogUrl() {
        viewModelScope.launch {
            preferences.updateCatalogUrl(_catalogUrl.value)
            _message.emit(
                if (_catalogUrl.value.isBlank()) "Vazio: usando o catálogo embutido"
                else "URL do catálogo salva"
            )
        }
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            _message.emit("Atualizando catálogo…")
            runCatching { catalog.refresh(force = true) }
                .onSuccess { _message.emit("Catálogo v${it.version}: ${it.count} itens") }
                .onFailure { _message.emit(it.message ?: "Falha ao atualizar o catálogo") }
            refreshAll()
        }
    }

    fun createBackup() {
        viewModelScope.launch {
            val result = SaveBackupManager.createBackup(app)
            _message.emit(
                if (result.file == null) "Nada para salvar ainda (jogue um pouco!)"
                else "Backup criado: ${result.files} arquivo(s), ${mb(result.bytes)}"
            )
            refreshAll()
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val result = SaveBackupManager.exportTo(app, uri)
            _message.emit(
                if (result.file == null) "Falha ao exportar o backup"
                else "Backup exportado (${mb(result.bytes)})"
            )
        }
    }

    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            val result = SaveBackupManager.restoreFrom(app, uri)
            _message.emit(
                result.error?.let { "Falha: $it" }
                    ?: "Restaurados ${result.restored} arquivo(s)" +
                    if (result.skipped > 0) ", ${result.skipped} ignorado(s)" else ""
            )
            refreshAll()
        }
    }

    fun deleteBackup(file: File) {
        viewModelScope.launch {
            SaveBackupManager.delete(app, file)
            refreshAll()
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) {
                StorageStats(
                    romsBytes = dirSize(StorageLayout.roms(app)),
                    savesBytes = SaveBackupManager.usage(app),
                    coversBytes = dirSize(StorageLayout.covers(app)),
                    games = repository.allGamesNow().size,
                    catalog = repository.catalogCount()
                )
            }
            _stats.value = stats
            _backups.value = SaveBackupManager.backups(app)
            _bios.value = BiosManager.requirements.mapNotNull { BiosManager.status(app, it.console) }
            _lastError.value = ErrorReporter.lastSaved(app)
        }
    }

    fun clearLastError() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                File(StorageLayout.root(app), "logs/last_error.txt").delete()
            }
            _lastError.value = null
        }
    }

    private fun dirSize(dir: File): Long =
        if (!dir.isDirectory) 0
        else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun mb(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
