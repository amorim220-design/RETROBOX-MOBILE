package com.retrobox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

import androidx.datastore.preferences.preferencesDataStore
import com.retrobox.domain.Console
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Preferências da biblioteca + configurações que sobrevivem ao app. */
class UserPreferencesRepository(private val context: Context) {
    private object PreferencesKeys {
        val LAST_CONSOLE = stringPreferencesKey("last_console")
        val CATALOG_URL = stringPreferencesKey("catalog_url")
        val CATALOG_LAST_REFRESH = longPreferencesKey("catalog_last_refresh")
        val CATALOG_LAST_VERSION = intPreferencesKey("catalog_last_version")
        val CATALOG_LAST_ERROR = stringPreferencesKey("catalog_last_error")
        /** Assinatura (SHA-256) do asset embutido já carregado. */
        val CATALOG_ASSET_SIGNATURE = stringPreferencesKey("catalog_asset_signature")
        val LAST_BIOS_CHECK = longPreferencesKey("last_bios_check")

        // Configurações de vídeo/execução, por console.
        fun linearFilter(console: String) = booleanPreferencesKey("video_linear_$console")
        fun aspectMode(console: String) = intPreferencesKey("video_aspect_$console")
        fun autoSaveOnExit(console: String) = booleanPreferencesKey("auto_save_$console")
        fun showControls(console: String) = booleanPreferencesKey("show_controls_$console")
    }

    val lastSelectedConsole: Flow<Console?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LAST_CONSOLE]?.let {
            try { Console.valueOf(it) } catch (e: Exception) { Console.PS1 }
        }
    }

    suspend fun updateLastConsole(console: Console) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_CONSOLE] = console.name
        }
    }

    // ------------------------------------------------------------- catálogo --

    /** URL configurada pelo usuário; vazio = usar o catálogo embutido (assets). */
    val catalogUrl: Flow<String> = context.dataStore.data.map { it[PreferencesKeys.CATALOG_URL].orEmpty() }

    val catalogLastRefresh: Flow<Long> = context.dataStore.data.map { it[PreferencesKeys.CATALOG_LAST_REFRESH] ?: 0L }

    val catalogLastVersion: Flow<Int> = context.dataStore.data.map { it[PreferencesKeys.CATALOG_LAST_VERSION] ?: 0 }

    val catalogLastError: Flow<String?> = context.dataStore.data.map { it[PreferencesKeys.CATALOG_LAST_ERROR] }

    /** SHA-256 do catálogo embutido que já foi carregado (para recarregar se mudar). */
    val catalogAssetSignature: Flow<String> =
        context.dataStore.data.map { it[PreferencesKeys.CATALOG_ASSET_SIGNATURE].orEmpty() }

    suspend fun updateCatalogAssetSignature(signature: String) {
        context.dataStore.edit { it[PreferencesKeys.CATALOG_ASSET_SIGNATURE] = signature }
    }

    suspend fun updateCatalogUrl(url: String) {
        context.dataStore.edit { it[PreferencesKeys.CATALOG_URL] = url.trim() }
    }

    suspend fun updateCatalogResult(version: Int, error: String?) {
        context.dataStore.edit { preferences ->
            if (error == null) {
                preferences[PreferencesKeys.CATALOG_LAST_REFRESH] = System.currentTimeMillis()
                preferences[PreferencesKeys.CATALOG_LAST_VERSION] = version
                preferences.remove(PreferencesKeys.CATALOG_LAST_ERROR)
            } else {
                preferences[PreferencesKeys.CATALOG_LAST_ERROR] = error
            }
        }
    }

    // --------------------------------------------------------------- vídeo ---

    fun videoPreferences(console: Console): Flow<VideoPreferences> = context.dataStore.data.map { p ->
        VideoPreferences(
            linearFilter = p[PreferencesKeys.linearFilter(console.id)] ?: true,
            aspectMode = p[PreferencesKeys.aspectMode(console.id)] ?: 0,
            autoSaveOnExit = p[PreferencesKeys.autoSaveOnExit(console.id)] ?: true,
            showControls = p[PreferencesKeys.showControls(console.id)] ?: true
        )
    }

    suspend fun videoPreferencesNow(console: Console): VideoPreferences =
        videoPreferences(console).first()

    suspend fun updateVideoPreferences(console: Console, prefs: VideoPreferences) {
        context.dataStore.edit { p ->
            p[PreferencesKeys.linearFilter(console.id)] = prefs.linearFilter
            p[PreferencesKeys.aspectMode(console.id)] = prefs.aspectMode
            p[PreferencesKeys.autoSaveOnExit(console.id)] = prefs.autoSaveOnExit
            p[PreferencesKeys.showControls(console.id)] = prefs.showControls
        }
    }
}

data class VideoPreferences(
    val linearFilter: Boolean = true,
    val aspectMode: Int = 0,
    val autoSaveOnExit: Boolean = true,
    val showControls: Boolean = true
)
