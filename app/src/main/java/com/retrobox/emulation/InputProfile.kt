package com.retrobox.emulation

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.retrobox.domain.Console
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.inputDataStore: DataStore<Preferences> by preferencesDataStore(name = "input")

/**
 * Ajustes de controle por console: zona morta, sensibilidade, mapeamento de
 * botões e aparencia do controle na tela.
 *
 * O mapeamento é `keycode do Android -> id libretro`, guardado por console
 * (cada núcleo tem suas manias — NES troca A/B, N64 usa ids diferentes…).
 */
data class InputProfile(
    val console: Console,
    /** zona morta do analógico, em % (0 = nenhuma) */
    val deadzone: Int = 15,
    /** sensibilidade horizontal/vertical, em % */
    val sensitivityX: Int = 100,
    val sensitivityY: Int = 100,
    /** um controle físico move mais de um jogador */
    val shareController: Boolean = false,
    /** estilo do controle na tela */
    val onScreenStyle: Int = 0,
    /** escala do controle na tela, em % */
    val onScreenScale: Int = 100,
    /** opacidade do controle na tela, em % */
    val onScreenOpacity: Int = 90,
    /** segundos até ocultar o controle na tela (0 = nunca) */
    val autoHideSeconds: Int = 0,
    /** vibração ao apertar botão na tela */
    val haptics: Boolean = true,
    /** mostra o analógico na tela */
    val showAnalog: Boolean = true,
    /** keycode do Android -> id libretro */
    val remap: Map<Int, Int> = emptyMap()
) {
    /**
     * Mapa efetivo: remap do usuário por cima do padrão do console.
     */
    fun mapping(): Map<Int, Int> {
        val base = ConsoleInput.defaultMapping(console)
        if (remap.isEmpty()) return base
        val result = base.toMutableMap()
        remap.forEach { (key, value) -> result[key] = value }
        return result
    }
}

class InputPreferencesRepository(private val context: Context) {

    private object Keys {
        fun deadzone(console: String) = intPreferencesKey("in_deadzone_$console")
        fun sensX(console: String) = intPreferencesKey("in_sensx_$console")
        fun sensY(console: String) = intPreferencesKey("in_sensy_$console")
        fun share(console: String) = booleanPreferencesKey("in_share_$console")
        fun style(console: String) = intPreferencesKey("in_style_$console")
        fun scale(console: String) = intPreferencesKey("in_scale_$console")
        fun opacity(console: String) = intPreferencesKey("in_opacity_$console")
        fun autoHide(console: String) = intPreferencesKey("in_autohide_$console")
        fun haptics(console: String) = booleanPreferencesKey("in_haptics_$console")
        fun analog(console: String) = booleanPreferencesKey("in_analog_$console")
        fun remap(console: String) = stringPreferencesKey("in_remap_$console")
    }

    fun profile(console: Console): Flow<InputProfile> = context.inputDataStore.data.map { prefs ->
        val id = console.id
        InputProfile(
            console = console,
            deadzone = prefs[Keys.deadzone(id)] ?: 15,
            sensitivityX = prefs[Keys.sensX(id)] ?: 100,
            sensitivityY = prefs[Keys.sensY(id)] ?: 100,
            shareController = prefs[Keys.share(id)] ?: false,
            onScreenStyle = prefs[Keys.style(id)] ?: 0,
            onScreenScale = prefs[Keys.scale(id)] ?: 100,
            onScreenOpacity = prefs[Keys.opacity(id)] ?: 90,
            autoHideSeconds = prefs[Keys.autoHide(id)] ?: 0,
            haptics = prefs[Keys.haptics(id)] ?: true,
            showAnalog = prefs[Keys.analog(id)] ?: true,
            remap = decodeRemap(prefs[Keys.remap(id)])
        )
    }

    suspend fun update(console: Console, transform: (InputProfile) -> InputProfile) {
        context.inputDataStore.edit { prefs ->
            val current = InputProfile(
                console = console,
                deadzone = prefs[Keys.deadzone(console.id)] ?: 15,
                sensitivityX = prefs[Keys.sensX(console.id)] ?: 100,
                sensitivityY = prefs[Keys.sensY(console.id)] ?: 100,
                shareController = prefs[Keys.share(console.id)] ?: false,
                onScreenStyle = prefs[Keys.style(console.id)] ?: 0,
                onScreenScale = prefs[Keys.scale(console.id)] ?: 100,
                onScreenOpacity = prefs[Keys.opacity(console.id)] ?: 90,
                autoHideSeconds = prefs[Keys.autoHide(console.id)] ?: 0,
                haptics = prefs[Keys.haptics(console.id)] ?: true,
                showAnalog = prefs[Keys.analog(console.id)] ?: true,
                remap = decodeRemap(prefs[Keys.remap(console.id)])
            )
            val updated = transform(current)
            prefs[Keys.deadzone(console.id)] = updated.deadzone
            prefs[Keys.sensX(console.id)] = updated.sensitivityX
            prefs[Keys.sensY(console.id)] = updated.sensitivityY
            prefs[Keys.share(console.id)] = updated.shareController
            prefs[Keys.style(console.id)] = updated.onScreenStyle
            prefs[Keys.scale(console.id)] = updated.onScreenScale
            prefs[Keys.opacity(console.id)] = updated.onScreenOpacity
            prefs[Keys.autoHide(console.id)] = updated.autoHideSeconds
            prefs[Keys.haptics(console.id)] = updated.haptics
            prefs[Keys.analog(console.id)] = updated.showAnalog
            prefs[Keys.remap(console.id)] = encodeRemap(updated.remap)
        }
    }

    /** Mapeia um botão físico para um id libretro. */
    suspend fun setRemap(console: Console, androidKeyCode: Int, libretroId: Int) {
        update(console) { profile ->
            val map = profile.remap.toMutableMap()
            map[androidKeyCode] = libretroId
            profile.copy(remap = map)
        }
    }

    suspend fun clearRemap(console: Console, androidKeyCode: Int) {
        update(console) { profile ->
            val map = profile.remap.toMutableMap()
            map.remove(androidKeyCode)
            profile.copy(remap = map)
        }
    }

    suspend fun resetRemap(console: Console) {
        update(console) { it.copy(remap = emptyMap()) }
    }

    private fun encodeRemap(map: Map<Int, Int>): String =
        map.entries.joinToString(",") { "${it.key}:${it.value}" }

    private fun decodeRemap(raw: String?): Map<Int, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<Int, Int>()
        raw.split(',').forEach { part ->
            val bits = part.split(':')
            if (bits.size == 2) {
                val key = bits[0].toIntOrNull()
                val value = bits[1].toIntOrNull()
                if (key != null && value != null) out[key] = value
            }
        }
        return out
    }
}
