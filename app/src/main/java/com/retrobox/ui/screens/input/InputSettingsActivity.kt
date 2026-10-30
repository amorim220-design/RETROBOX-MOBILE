package com.retrobox.ui.screens.input

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retrobox.domain.Console
import com.retrobox.emulation.ConsoleInput
import com.retrobox.emulation.GamepadManager
import com.retrobox.emulation.InputPreferencesRepository
import com.retrobox.emulation.InputProfile
import com.retrobox.ui.emulator.TouchStyle
import com.retrobox.ui.theme.BackgroundDark
import com.retrobox.ui.theme.NeonBlue
import com.retrobox.ui.theme.RetroBoxTheme
import com.retrobox.ui.theme.SurfaceDark
import com.retrobox.ui.theme.SurfaceVariantDark
import kotlinx.coroutines.launch

/**
 * Tela de **Controles**: zona morta, sensibilidade, mapeamento de botões,
 * aparência do controle na tela e diagnóstico dos controles conectados.
 *
 * Fica numa Activity própria porque é aberta de dentro do emulador.
 */
class InputSettingsActivity : ComponentActivity() {

    private val waitingKey = mutableStateOf<Int?>(null)
    private val capturedKey = mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = InputPreferencesRepository(this)
        val gamepads = GamepadManager(this)

        setContent {
            RetroBoxTheme {
                Surface(color = BackgroundDark) {
                    InputSettingsScreen(
                        repository = repository,
                        gamepads = gamepads,
                        initialConsole = Console.fromId(intent.getStringExtra(EXTRA_CONSOLE) ?: ""),
                        waitingKey = waitingKey.value,
                        capturedKey = capturedKey.value,
                        onWaitingKey = { waitingKey.value = it },
                        onCaptured = { capturedKey.value = it },
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (waitingKey.value != null && event.action == KeyEvent.ACTION_DOWN) {
            // consome o botão para o Android não abrir menu por cima
            capturedKey.value = event.keyCode
            waitingKey.value = null
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_CONSOLE = "extra_console"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputSettingsScreen(
    repository: InputPreferencesRepository,
    gamepads: GamepadManager,
    initialConsole: Console?,
    waitingKey: Int?,
    capturedKey: Int?,
    onWaitingKey: (Int?) -> Unit,
    onCaptured: (Int) -> Unit,
    onBack: () -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var console by remember { mutableStateOf(initialConsole ?: Console.SNES) }

    val profile by remember(console) { repository.profile(console) }
        .collectAsState(initial = InputProfile(console))

    val devices = remember { gamepads.devices() }
    val axesInfo = remember { gamepads.axisInfo() }

    // botão capturado -> aplica o remap
    LaunchedEffect(capturedKey) {
        val key = capturedKey ?: return@LaunchedEffect
        val target = waitingKey ?: return@LaunchedEffect
        val mapping = profile.mapping()
        val retroId = mapping[target] ?: return@LaunchedEffect
        // o botão capturado passa a ser o do alvo (troca os dois de lugar)
        val swapped = profile.remap.entries.firstOrNull { it.value == retroId }
        scope.launch {
            if (swapped != null) {
                repository.setRemap(console, key, swapped.key)
                repository.setRemap(console, target, retroId)
            } else {
                repository.setRemap(console, key, retroId)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("CONTROLES", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(BackgroundDark, SurfaceDark))),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Console.entries.toList()) { option ->
                        FilterChip(
                            selected = console == option,
                            onClick = { console = option },
                            label = { Text(option.displayName) }
                        )
                    }
                }
            }

            item {
                Section("ANALÓGICO") {
                    SliderRow(
                        label = "Zona morta",
                        value = profile.deadzone,
                        range = 0..60,
                        suffix = "%",
                        onChange = { value ->
                            scope.launch { repository.update(console) { it.copy(deadzone = value) } }
                        }
                    )
                    SliderRow(
                        label = "Sensibilidade horizontal",
                        value = profile.sensitivityX,
                        range = 25..200,
                        suffix = "%",
                        onChange = { value ->
                            scope.launch { repository.update(console) { it.copy(sensitivityX = value) } }
                        }
                    )
                    SliderRow(
                        label = "Sensibilidade vertical",
                        value = profile.sensitivityY,
                        range = 25..200,
                        suffix = "%",
                        onChange = { value ->
                            scope.launch { repository.update(console) { it.copy(sensitivityY = value) } }
                        }
                    )
                    SwitchRow(
                        label = "Compartilhar controle (um controle move todos)",
                        checked = profile.shareController,
                        onChange = { checked ->
                            scope.launch { repository.update(console) { it.copy(shareController = checked) } }
                        }
                    )
                }
            }

            item {
                Section("CONTROLE NA TELA") {
                    Text("Estilo", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(TouchStyle.entries.toList()) { style ->
                            FilterChip(
                                selected = profile.onScreenStyle == style.id,
                                onClick = {
                                    scope.launch {
                                        repository.update(console) { it.copy(onScreenStyle = style.id) }
                                    }
                                },
                                label = {
                                    Text(style.name.lowercase().replaceFirstChar { it.uppercase() })
                                }
                            )
                        }
                    }
                    SliderRow(
                        label = "Escala",
                        value = profile.onScreenScale,
                        range = 50..160,
                        suffix = "%",
                        onChange = { value ->
                            scope.launch { repository.update(console) { it.copy(onScreenScale = value) } }
                        }
                    )
                    SliderRow(
                        label = "Opacidade",
                        value = profile.onScreenOpacity,
                        range = 10..100,
                        suffix = "%",
                        onChange = { value ->
                            scope.launch { repository.update(console) { it.copy(onScreenOpacity = value) } }
                        }
                    )
                    SliderRow(
                        label = "Esconder depois de",
                        value = profile.autoHideSeconds,
                        range = 0..60,
                        suffix = " s",
                        onChange = { value ->
                            scope.launch { repository.update(console) { it.copy(autoHideSeconds = value) } }
                        }
                    )
                    SwitchRow(
                        label = "Vibrar ao apertar",
                        checked = profile.haptics,
                        onChange = { checked ->
                            scope.launch { repository.update(console) { it.copy(haptics = checked) } }
                        }
                    )
                    SwitchRow(
                        label = "Mostrar analógico na tela",
                        checked = profile.showAnalog,
                        onChange = { checked ->
                            scope.launch { repository.update(console) { it.copy(showAnalog = checked) } }
                        }
                    )
                }
            }

            item {
                Section("MAPEAR BOTÕES") {
                    Text(
                        "Toque em “Mapear” e depois aperte o botão do controle " +
                            "(físico ou Bluetooth) que quer usar.",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    val mapping = profile.mapping()
                    remappableKeys().forEach { (androidKey, label) ->
                        val retroId = mapping[androidKey] ?: 0
                        val isCustom = profile.remap.containsKey(androidKey)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                label,
                                color = Color.White,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (isCustom) {
                                Text(
                                    "custom",
                                    color = NeonBlue,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                            Text(
                                ConsoleInput.labelFor(retroId),
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { onWaitingKey(androidKey) },
                                contentPadding = PaddingValues(4.dp)
                            ) {
                                Text(
                                    if (waitingKey == androidKey) "Aperte…" else "Mapear",
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch { repository.resetRemap(console) }
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Restaurar padrão")
                    }
                }
            }

            item {
                Section("CONTROLES CONECTADOS") {
                    if (devices.isEmpty()) {
                        Text(
                            "Nenhum controle encontrado.",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp
                        )
                    } else {
                        devices.forEach { device ->
                            Text(
                                "${device.name} (id ${device.id})" +
                                    if (device.isGamepad) " · gamepad" else " · teclado/toque",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp
                            )
                        }
                    }
                    if (axesInfo.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(axesInfo, color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

/** Botões que o usuário pode remapear (keycode do Android -> rótulo). */
private fun remappableKeys(): List<Pair<Int, String>> = listOf(
    KeyEvent.KEYCODE_BUTTON_A to "A",
    KeyEvent.KEYCODE_BUTTON_B to "B",
    KeyEvent.KEYCODE_BUTTON_X to "X",
    KeyEvent.KEYCODE_BUTTON_Y to "Y",
    KeyEvent.KEYCODE_BUTTON_L1 to "L1 / L",
    KeyEvent.KEYCODE_BUTTON_R1 to "R1 / R",
    KeyEvent.KEYCODE_BUTTON_L2 to "L2",
    KeyEvent.KEYCODE_BUTTON_R2 to "R2",
    KeyEvent.KEYCODE_BUTTON_SELECT to "SELECT",
    KeyEvent.KEYCODE_BUTTON_START to "START",
    KeyEvent.KEYCODE_DPAD_UP to "Direcional ↑",
    KeyEvent.KEYCODE_DPAD_DOWN to "Direcional ↓",
    KeyEvent.KEYCODE_DPAD_LEFT to "Direcional ←",
    KeyEvent.KEYCODE_DPAD_RIGHT to "Direcional →"
)

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = SurfaceVariantDark, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                color = NeonBlue,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Int,
    range: IntRange,
    suffix: String,
    onChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Text("$value$suffix", color = NeonBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first).coerceAtLeast(1) - 1
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
