package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.Console
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class UserPreferencesRepository(private val context: Context) {
    private object PreferencesKeys {
        val LAST_CONSOLE = stringPreferencesKey("last_console")
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
}
