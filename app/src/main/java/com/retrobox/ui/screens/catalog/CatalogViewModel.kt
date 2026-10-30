package com.retrobox.ui.screens.catalog

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.retrobox.RetroBoxApplication
import com.retrobox.catalog.CatalogException
import com.retrobox.data.CatalogEntry
import com.retrobox.data.DownloadRecord
import com.retrobox.data.Game
import com.retrobox.data.StorageLayout
import com.retrobox.domain.Console
import com.retrobox.download.DownloadScheduler
import com.retrobox.download.RomDownloader
import com.retrobox.emulation.CoverStore
import com.retrobox.emulation.GameTitle
import com.retrobox.emulation.RomIdentifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CatalogItem(
    val entry: CatalogEntry,
    val record: DownloadRecord?,
    val installedGame: Game?
) {
    val installed: Boolean get() = installedGame != null
}

class CatalogViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RetroBoxApplication
    private val repository = app.gameRepository
    private val catalog = app.catalogRepository
    private val preferences = app.userPreferencesRepository

    private val _console = MutableStateFlow<Console?>(null)
    val console: StateFlow<Console?> = _console.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val lastRefresh = preferences.catalogLastRefresh
    val lastError = preferences.catalogLastError

    val items: StateFlow<List<CatalogItem>> = combine(
        repository.catalog,
        repository.downloads,
        repository.allGames,
        _console
    ) { entries, downloads, games, console ->
        val downloadsById = downloads.associateBy { it.catalogId }
        val gamesByCatalog = games.filter { it.catalogId != null }.associateBy { it.catalogId }
        entries
            .filter { console == null || it.console == console }
            .map { entry ->
                CatalogItem(
                    entry = entry,
                    record = downloadsById[entry.id],
                    installedGame = gamesByCatalog[entry.id]
                )
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val message: SharedFlow<String> = _message

    fun selectConsole(console: Console?) {
        _console.value = console
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            val result = runCatching { catalog.refresh(force = true) }
            _refreshing.value = false
            result.onSuccess { r ->
                _message.emit(
                    "Catálogo v${r.version}: ${r.count} itens" +
                        if (r.signatureVerified) " (assinatura Ed25519)" else " (hash fixado)"
                )
                if (r.warnings.isNotEmpty()) {
                    _message.emit("${r.warnings.size} item(ns) ignorado(s): ${r.warnings.first()}")
                }
            }.onFailure { e ->
                val message = when (e) {
                    is CatalogException -> e.message ?: "Catálogo inválido."
                    else -> "Não foi possível atualizar o catálogo: ${e.message}"
                }
                runCatching { preferences.updateCatalogResult(0, message) }
                _message.emit(message)
            }
        }
    }

    fun download(entry: CatalogEntry) {
        if (entry.url.isNullOrBlank()) {
            viewModelScope.launch { _message.emit("Use '${entry.title}' com o seu próprio arquivo.") }
            return
        }
        DownloadScheduler.enqueue(app, entry)
        viewModelScope.launch { _message.emit("Baixando ${entry.title}…") }
    }

    fun cancel(entry: CatalogEntry) {
        DownloadScheduler.cancel(app, entry.id)
        viewModelScope.launch {
            repository.setDownload(
                com.retrobox.data.DownloadRecord(entry.id, com.retrobox.data.DownloadState.CANCELED)
            )
        }
    }

    /**
     * "Traga seu próprio arquivo": valida o SHA-256 do catálogo (se houver),
     * copia para `roms/<console>/` e entra na biblioteca como se tivesse sido
     * baixado.
     */
    fun installLocal(entry: CatalogEntry, uri: Uri) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryName(uri)
                    val fileName = entry.fileName.ifBlank { name ?: "${entry.id}.rom" }
                    val target = File(StorageLayout.romsFor(context, entry.console.id), fileName)
                    target.parentFile?.mkdirs()
                    val temp = RomDownloader.partFile(context, "local-${entry.id}")
                    temp.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                    } ?: throw IllegalStateException("sem permissão de leitura")

                    if (entry.sha256.isNotBlank()) {
                        val sha = RomIdentifier.sha256(temp)
                        if (!sha.equals(entry.sha256, ignoreCase = true)) {
                            temp.delete()
                            throw IllegalStateException(
                                "O arquivo não é o esperado (SHA-256 diferente do catálogo)."
                            )
                        }
                    }
                    if (target.exists()) target.delete()
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                    target
                }
            }

            result.onSuccess { file ->
                val title = GameTitle.clean(entry.fileName).ifBlank { entry.title }
                val cover = CoverStore.coverFor(context, entry.console, entry.fileName)
                val added = repository.addDownloaded(entry, file.absolutePath, title, cover)
                if (!added) file.delete()
                repository.setDownload(
                    com.retrobox.data.DownloadRecord(
                        entry.id,
                        com.retrobox.data.DownloadState.COMPLETED,
                        bytes = file.length(),
                        totalBytes = file.length()
                    )
                )
                _message.emit("$title adicionado à biblioteca")
            }.onFailure { e ->
                _message.emit(e.message ?: "Não foi possível importar o arquivo.")
            }
        }
    }

    private fun queryName(uri: Uri): String? {
        var name: String? = null
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
            }
        }
        return name
    }
}
