package com.retrobox.ui.emulator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.retrobox.RetroBoxApplication
import com.retrobox.data.Game
import com.retrobox.data.UserPreferencesRepository
import com.retrobox.data.VideoPreferences
import com.retrobox.domain.Console
import com.retrobox.emulation.BiosManager
import com.retrobox.emulation.ConsoleInput
import com.retrobox.emulation.EmulatorException
import com.retrobox.emulation.EmulatorSession
import com.retrobox.emulation.ErrorReporter
import com.retrobox.emulation.GamepadManager
import com.retrobox.emulation.InputPreferencesRepository
import com.retrobox.emulation.InputProfile
import com.retrobox.emulation.NativeEmulator
import com.retrobox.emulation.RetroButton
import com.retrobox.ui.screens.bios.BiosScreen
import com.retrobox.ui.screens.bios.BiosViewModel
import com.retrobox.ui.theme.RetroBoxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class EmulatorActivity : ComponentActivity() {

    private lateinit var session: EmulatorSession
    private lateinit var preferences: UserPreferencesRepository
    private lateinit var inputPreferences: InputPreferencesRepository
    private lateinit var gamepads: GamepadManager
    private var startJob: Job? = null

    private var console: Console? = null
    private var gameTitle: String = ""
    private var romPath: String = ""

    private var status by mutableStateOf<EmulatorStatus>(EmulatorStatus.Loading)
    private var showMenu by mutableStateOf(false)
    private var showBiosScreen by mutableStateOf(false)
    private var showControls by mutableStateOf(true)
    private var fastForward by mutableStateOf(false)
    private var smoothFilter by mutableStateOf(true)
    private var aspectMode by mutableStateOf(0)
    private var autoSaveOnExit by mutableStateOf(true)
    private var fps by mutableStateOf(0.0)
    private var geometry by mutableStateOf("")

    /** Perfil de controles do console atual (zona morta, mapeamento, estilo). */
    private var inputProfile by mutableStateOf<InputProfile?>(null)
    private var lastInputEvent by mutableStateOf("")
    private var showInputDiagnostics by mutableStateOf(false)
    private var lastTouchActivity by mutableStateOf(0L)
    private val announcedGamepads = mutableSetOf<Int>()

    /** Slot de save state em uso (0..7). */
    private var stateSlot by mutableStateOf(0)

    /** Estado da checagem de BIOS: bloqueia o start só quando falta arquivo. */
    private var biosWarning by mutableStateOf<BiosManager.Status?>(null)
    private var biosGateOpen by mutableStateOf(false)
    private var pendingSurface: Surface? = null
    private var pendingTexture: SurfaceTexture? = null

    private var errorDetails: String = ""
    private var lastErrorMessage: String = ""

    private val biosPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val warning = biosWarning ?: return@registerForActivityResult
        val firstFile = warning.requirement.files.firstOrNull() ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = BiosManager.import(this@EmulatorActivity, warning.requirement.console, uri, firstFile.name)
            toast(result.message)
            val updated = BiosManager.status(this@EmulatorActivity, warning.requirement.console)
            biosWarning = updated?.takeIf { !it.satisfied }
            if (biosWarning == null) {
                biosGateOpen = true
                maybeStart()
            }
        }
    }

    private val gamepadKeys = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to RetroButton.A,
        KeyEvent.KEYCODE_BUTTON_B to RetroButton.B,
        KeyEvent.KEYCODE_BUTTON_X to RetroButton.X,
        KeyEvent.KEYCODE_BUTTON_Y to RetroButton.Y,
        KeyEvent.KEYCODE_BUTTON_L1 to RetroButton.L,
        KeyEvent.KEYCODE_BUTTON_R1 to RetroButton.R,
        KeyEvent.KEYCODE_BUTTON_L2 to RetroButton.L2,
        KeyEvent.KEYCODE_BUTTON_R2 to RetroButton.R2,
        KeyEvent.KEYCODE_BUTTON_SELECT to RetroButton.SELECT,
        KeyEvent.KEYCODE_BUTTON_START to RetroButton.START,
        KeyEvent.KEYCODE_DPAD_UP to RetroButton.UP,
        KeyEvent.KEYCODE_DPAD_DOWN to RetroButton.DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to RetroButton.LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to RetroButton.RIGHT,
        KeyEvent.KEYCODE_ENTER to RetroButton.START,
        KeyEvent.KEYCODE_SPACE to RetroButton.A,
        KeyEvent.KEYCODE_ALT_LEFT to RetroButton.SELECT,
        KeyEvent.KEYCODE_ALT_RIGHT to RetroButton.START
    )

    /**
     * Mapa efetivo de teclas: remap do usuário por cima do padrão do console.
     */
    private fun keyMapping(): Map<Int, Int> =
        inputProfile?.mapping() ?: gamepadKeys


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val app = application as RetroBoxApplication
        preferences = app.userPreferencesRepository
        inputPreferences = InputPreferencesRepository(this)
        gamepads = GamepadManager(this)
        session = EmulatorSession(this)

        console = Console.fromId(intent.getStringExtra(EXTRA_CONSOLE) ?: "")
        romPath = intent.getStringExtra(EXTRA_PATH).orEmpty()
        gameTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        status = runCatching {
            val active = console ?: throw EmulatorException("Console desconhecido.")
            session.prepare(active, romPath, gameTitle)
            EmulatorStatus.Loading
        }.getOrElse {
            val message = it.message ?: "Falha ao preparar o jogo."
            fail(message)
        }

        // configurações por console (vídeo/controles/autosave)
        console?.let { active ->
            lifecycleScope.launch {
                val loaded = preferences.videoPreferencesNow(active)
                smoothFilter = loaded.linearFilter
                aspectMode = loaded.aspectMode
                showControls = loaded.showControls
                autoSaveOnExit = loaded.autoSaveOnExit

                // perfil de controles (zona morta, mapeamento, estilo do overlay)
                lifecycleScope.launch {
                    inputPreferences.profile(active).collect { inputProfile = it }
                }

                val bios = BiosManager.status(this@EmulatorActivity, active)
                if (bios != null && bios.requirement.files.isNotEmpty() && !bios.satisfied) {
                    biosWarning = bios
                } else {
                    biosGateOpen = true
                }
                maybeStart()
            }
        } ?: run { biosGateOpen = true }

        setContent {
            RetroBoxTheme {
                EmulatorContent()
            }
        }
    }

    @Composable
    private fun EmulatorContent() {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

            if (!showBiosScreen) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context -> createTextureView(context) }
                )
            }

            when (val current = status) {
                is EmulatorStatus.Failed -> ErrorOverlay(current.message)
                EmulatorStatus.Loading -> LoadingOverlay()
                EmulatorStatus.Running -> {
                    val active = console

                    // esconde o controle na tela depois de X segundos sem uso
                    LaunchedEffect(inputProfile?.autoHideSeconds) {
                        val seconds = inputProfile?.autoHideSeconds ?: 0
                        while (seconds > 0) {
                            delay(500)
                            if (System.currentTimeMillis() - lastTouchActivity > seconds * 1000L) {
                                showControls = false
                            }
                        }
                    }

                    if (showControls && !showMenu && active != null) {
                        TouchControls(
                            console = active,
                            layout = ConsoleInput.forConsole(active),
                            profile = inputProfile ?: InputProfile(active),
                            onPress = { id, pressed ->
                                NativeEmulator.nativeSetButton(0, id, pressed)
                                lastTouchActivity = System.currentTimeMillis()
                            },
                            onAnalog = { index, axis, value ->
                                pushAxis(index, axis, value)
                                lastTouchActivity = System.currentTimeMillis()
                            },
                            onActivity = { lastTouchActivity = System.currentTimeMillis() }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (fastForward) Badge("FF")
                        if (com.retrobox.BuildConfig.DEBUG && geometry.isNotEmpty()) Badge(geometry)
                        Badge("${fps.toInt()} FPS")
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                        }
                    }

                    // Diagnóstico de input (mostra o último evento recebido)
                    if (showInputDiagnostics && lastInputEvent.isNotBlank()) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp)
                        ) {
                            Text(
                                lastInputEvent,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    LaunchedEffect(Unit) {
                        while (status is EmulatorStatus.Running) {
                            fps = NativeEmulator.nativeGetFps()
                            geometry = NativeEmulator.nativeGeometryInfo()
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                }
            }

            biosWarning?.let { warning ->
                BiosWarningDialog(
                    status = warning,
                    onContinue = {
                        biosWarning = null
                        biosGateOpen = true
                        maybeStart()
                    },
                    onImport = { biosPicker.launch(arrayOf("*/*")) },
                    onManage = {
                        biosWarning = null
                        showBiosScreen = true
                    }
                )
            }

            if (showBiosScreen) {
                Box(modifier = Modifier.fillMaxSize()) {
                    BiosScreen(
                        viewModel = androidx.lifecycle.viewmodel.compose.viewModel<BiosViewModel>(),
                        onBack = {
                            showBiosScreen = false
                            lifecycleScope.launch {
                                val bios = console?.let { BiosManager.status(this@EmulatorActivity, it) }
                                if (bios != null && bios.satisfied) {
                                    biosWarning = null
                                    biosGateOpen = true
                                }
                                maybeStart()
                            }
                        }
                    )
                }
            }

            if (showMenu) MenuOverlay()
        }
    }

    @Composable
    private fun BiosWarningDialog(
        status: BiosManager.Status,
        onContinue: () -> Unit,
        onImport: () -> Unit,
        onManage: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("BIOS de ${status.requirement.console.displayName} não encontrada") },
            text = {
                Column {
                    Text(status.requirement.note, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Arquivos esperados: " +
                            status.requirement.files.joinToString(", ") { it.name },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "O jogo pode abrir mesmo assim (o núcleo usa HLE), mas alguns títulos falham.",
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onContinue) { Text("Continuar assim") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onManage) { Text("Gerenciar") }
                    TextButton(onClick = onImport) { Text("Importar") }
                }
            }
        )
    }

    @Composable
    private fun Badge(text: String) {
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }

    @Composable
    private fun LoadingOverlay() {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("Preparando o jogo…", color = Color.White)
            }
        }
    }

    @Composable
    private fun ErrorOverlay(message: String) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Não foi possível iniciar",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                Text(message, color = Color(0xFFFF8A80), fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "${session.console?.displayName ?: "—"} · ${session.coreName}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
                if (errorDetails.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = Color.White.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            errorDetails.take(900),
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        copyToClipboard(errorDetails.ifBlank { message })
                        toast("Detalhes copiados")
                    }) { Text("Copiar detalhes") }
                    OutlinedButton(onClick = {
                        showBiosScreen = true
                    }) { Text("BIOS") }
                    Button(onClick = { finish() }) { Text("Voltar") }
                }
            }
        }
    }

    @Composable
    private fun MenuOverlay() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier
                        .width(340.dp)
                        .heightIn(max = 620.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        gameTitle.ifBlank { "Jogo pausado" },
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${console?.displayName ?: "—"} · ${session.coreName}",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))

                    MenuAction("Continuar") { showMenu = false }
                    MenuAction("Salvar estado (slot ${stateSlot + 1})") {
                        val ok = session.saveState(stateSlot)
                        showMenu = false
                        toast(if (ok) "Estado salvo no slot ${stateSlot + 1}" else "Este núcleo não suporta save state")
                    }
                    MenuAction("Carregar estado (slot ${stateSlot + 1})") {
                        val ok = session.loadState(stateSlot)
                        showMenu = false
                        toast(if (ok) "Estado do slot ${stateSlot + 1} carregado" else "Slot ${stateSlot + 1} vazio")
                    }
                    MenuAction("Trocar slot de save (agora: ${stateSlot + 1}/8)") {
                        stateSlot = (stateSlot + 1) % 8
                    }
                    MenuAction("Reiniciar jogo") {
                        NativeEmulator.nativeReset()
                        showMenu = false
                    }
                    MenuAction(if (fastForward) "Fast-forward: ligado" else "Fast-forward: desligado") {
                        fastForward = !fastForward
                        NativeEmulator.nativeSetFastForward(fastForward)
                    }
                    MenuAction(if (smoothFilter) "Filtro: suave" else "Filtro: nítido") {
                        smoothFilter = !smoothFilter
                        NativeEmulator.nativeSetLinearFilter(smoothFilter)
                        persistSettings()
                    }
                    MenuAction(
                        when (aspectMode) {
                            0 -> "Aspecto: ajustar"
                            1 -> "Aspecto: esticar"
                            else -> "Aspecto: inteiro"
                        }
                    ) {
                        aspectMode = (aspectMode + 1) % 3
                        NativeEmulator.nativeSetAspectMode(aspectMode)
                        persistSettings()
                    }
                    MenuAction(if (showControls) "Esconder controles" else "Mostrar controles") {
                        showControls = !showControls
                        showMenu = false
                        persistSettings()
                    }
                    MenuAction(
                        if (showInputDiagnostics) "Diagnóstico de input: ligado" else "Diagnóstico de input: desligado"
                    ) {
                        showInputDiagnostics = !showInputDiagnostics
                        lastInputEvent = ""
                    }
                    MenuAction(
                        "Controles: zona morta ${inputProfile?.deadzone ?: 15}%" +
                            " · estilo ${TouchStyle.of(inputProfile?.onScreenStyle ?: 0).displayName.lowercase()}"
                    ) {
                        showMenu = false
                        startActivity(
                            android.content.Intent(
                                this@EmulatorActivity,
                                com.retrobox.ui.screens.input.InputSettingsActivity::class.java
                            ).putExtra(
                                com.retrobox.ui.screens.input.InputSettingsActivity.EXTRA_CONSOLE,
                                console?.id
                            )
                        )
                    }
                    MenuAction("BIOS e firmware") {
                        showMenu = false
                        showBiosScreen = true
                    }
                    MenuAction("Sair") {
                        showMenu = false
                        finish()
                    }
                }
            }
        }
    }

    @Composable
    private fun MenuAction(label: String, onClick: () -> Unit) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(label, color = Color.White)
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("RetroBox", text))
    }

    private fun persistSettings() {
        val active = console ?: return
        val snapshot = VideoPreferences(
            linearFilter = smoothFilter,
            aspectMode = aspectMode,
            autoSaveOnExit = autoSaveOnExit,
            showControls = showControls
        )
        lifecycleScope.launch { preferences.updateVideoPreferences(active, snapshot) }
    }

    private fun fail(message: String): EmulatorStatus {
        lastErrorMessage = message
        val report = ErrorReporter.report(
            context = this,
            console = console?.displayName ?: "?",
            core = session.coreName,
            romPath = romPath.takeIf { it.isNotBlank() },
            message = message
        )
        errorDetails = report.details
        return EmulatorStatus.Failed(message)
    }

    /** Inicia só quando a superfície e a checagem de BIOS estiverem prontas. */
    private fun maybeStart() {
        if (!biosGateOpen) return
        val surface = pendingSurface ?: return
        if (status is EmulatorStatus.Failed) return
        if (startJob?.isActive == true || session.started) return

        status = EmulatorStatus.Loading
        startJob = lifecycleScope.launch {
            val error = withContext(Dispatchers.Default) { session.start(surface) }
            status = if (error.isEmpty()) {
                NativeEmulator.nativeSetLinearFilter(smoothFilter)
                NativeEmulator.nativeSetAspectMode(aspectMode)
                EmulatorStatus.Running
            } else {
                fail(error)
            }
        }
    }

    /**
     * A `TextureView` is used instead of a `SurfaceView` on purpose: a SurfaceView
     * lives in its own SurfaceFlinger layer and the app window gets an input crop
     * over that area, so the Compose overlay (on-screen gamepad) would never
     * receive the touches. With a TextureView the video is composited inside the
     * window and every touch reaches the UI.
     */
    private fun createTextureView(context: Context): TextureView {
        return TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    android.util.Log.i(
                        "RetroBoxNative",
                        "textureAvailable ${width}x$height display=${resources.displayMetrics}"
                    )
                    if (status is EmulatorStatus.Failed) {
                        texture.release()
                        return
                    }
                    pendingTexture = texture
                    pendingSurface = Surface(texture)
                    maybeStart()
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    android.util.Log.i("RetroBoxNative", "textureResized ${width}x$height")
                }

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    startJob?.cancel()
                    // IMPORTANT: para o core ANTES de liberar a surface.
                    // Se a surface sumir com o thread de render ainda ativo, o
                    // app toma SIGSEGV (e o Android mata o processo).
                    session.stop()
                    pendingSurface?.release()
                    pendingSurface = null
                    pendingTexture = null
                    if (!isFinishing) status = EmulatorStatus.Loading
                    return true
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            if (showBiosScreen) {
                showBiosScreen = false
                return true
            }
            if (showMenu) {
                showMenu = false
                return true
            }
            showMenu = status is EmulatorStatus.Running
            return true
        }
        if (status !is EmulatorStatus.Running) return super.dispatchKeyEvent(event)

        val retroId = keyMapping()[event.keyCode]
        if (retroId == null) return super.dispatchKeyEvent(event)

        // Controle físico (USB/Bluetooth): avisa qual controle entrou e em que
        // jogador ele ficou.
        val port = 0  // single-player por padrão; multi-jogador é feito via perfil
        val pressed = event.action == KeyEvent.ACTION_DOWN
        if (pressed) {
            announceGamepad(event.deviceId, port)
            lastTouchActivity = System.currentTimeMillis()
            // usar um controle físico mostra o overlay de novo (e o timer reinicia)
            showControls = true
        }
        NativeEmulator.nativeSetButton(port, retroId, pressed)
        lastInputEvent = "key ${KeyEvent.keyCodeToString(event.keyCode)} -> ${ConsoleInput.labelFor(retroId)}"
        return true
    }

    /**
     * Eixos analógicos e gatilhos dos controles (e o "chapéu" do direcional).
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (status !is EmulatorStatus.Running) return super.dispatchGenericMotionEvent(event)
        val port = 0  // single-player por padrão
        var handled = false
        var changed = false

        // Direcional analógico (chapéu)
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (hatX != 0f || hatY != 0f) {
                val mapping = keyMapping()
                setMapped(mapping, KeyEvent.KEYCODE_DPAD_LEFT, port, hatX < -0.5f)
                setMapped(mapping, KeyEvent.KEYCODE_DPAD_RIGHT, port, hatX > 0.5f)
                setMapped(mapping, KeyEvent.KEYCODE_DPAD_UP, port, hatY < -0.5f)
                setMapped(mapping, KeyEvent.KEYCODE_DPAD_DOWN, port, hatY > 0.5f)
                handled = true
                changed = true
            }
        }

        // Sticks
        val deadzone = (inputProfile?.deadzone ?: 15) / 100f
        val sensitivityX = (inputProfile?.sensitivityX ?: 100) / 100f
        val sensitivityY = (inputProfile?.sensitivityY ?: 100) / 100f
        var lastAxisName = ""
        var lastAxisValue = 0f
        listOf(
            MotionEvent.AXIS_X to (0 to sensitivityX),
            MotionEvent.AXIS_Y to (1 to sensitivityY),
            MotionEvent.AXIS_Z to (0 to sensitivityX),
            MotionEvent.AXIS_RZ to (1 to sensitivityY)
        ).forEach { (axis, slotAndSens) ->
            val raw = event.getAxisValue(axis)
            if (raw == 0f) return@forEach
            val slot = slotAndSens.first
            val sens = slotAndSens.second
            val value = applyDeadzone(raw, deadzone) * sens
            pushAxis(slot, if (axis == MotionEvent.AXIS_X || axis == MotionEvent.AXIS_Z) 0 else 1, value, port)
            handled = true
            changed = true
            lastAxisName = MotionEvent.axisToString(axis)
            lastAxisValue = raw
        }

        // Gatilhos analógicos viram L2/R2
        val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        if (lt > 0.5f) {
            NativeEmulator.nativeSetButton(port, RetroButton.L2, true)
            handled = true; changed = true
        }
        if (rt > 0.5f) {
            NativeEmulator.nativeSetButton(port, RetroButton.R2, true)
            handled = true; changed = true
        }

        if (changed) {
            announceGamepad(event.deviceId, port)
            lastTouchActivity = System.currentTimeMillis()
            lastInputEvent = "eixo $lastAxisName = ${"%.2f".format(lastAxisValue)}"
        }
        return handled || super.dispatchGenericMotionEvent(event)
    }

    /** Aplica zona morta e ganho; devolve -1..1. */
    private fun applyDeadzone(value: Float, deadzone: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= deadzone) return 0f
        val scaled = (magnitude - deadzone) / (1f - deadzone)
        return if (value < 0) -scaled else scaled
    }

    /** Envia um eixo para o core (índice 0 = stick esquerdo). */
    private fun pushAxis(index: Int, axis: Int, value: Float, port: Int = 0) {
        val scaled = (value.coerceIn(-1f, 1f) * 32767f).toInt()
        NativeEmulator.nativeSetAxis(port, index, axis, scaled)
    }

    private fun setMapped(mapping: Map<Int, Int>, keyCode: Int, port: Int, pressed: Boolean) {
        val retroId = mapping[keyCode] ?: return
        NativeEmulator.nativeSetButton(port, retroId, pressed)
    }

    /** Porta do controle: a primeira livre; "compartilhar controle" devolve sempre a 1. */
    private fun portForDevice(deviceId: Int, source: Int): Int {
        if (inputProfile?.shareController == true) return 1
        return gamepads.portFor(deviceId)
    }

    /** Avisa (uma vez) o controle que acabou de conectar. */
    private fun announceGamepad(deviceId: Int, port: Int) {
        if (!announcedGamepads.add(deviceId)) return
        val name = gamepads.deviceName(deviceId) ?: "Controle"
        toast("Controle: $name (jogador $port)")
    }

    override fun onPause() {
        super.onPause()
        NativeEmulator.nativeSetFastForward(false)
        // autosave ao sair do app (não em rotação nem com o menu aberto)
        if (autoSaveOnExit && session.started && !isChangingConfigurations) {
            runCatching { session.saveState() }
        }
    }

    override fun onDestroy() {
        // garante que o core pare mesmo se a textura não tiver sido destruída
        if (session.started) {
            runCatching { session.stop() }
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_CONSOLE = "extra_console"
        private const val EXTRA_PATH = "extra_path"
        private const val EXTRA_TITLE = "extra_title"

        fun intent(context: Context, game: Game): Intent =
            Intent(context, EmulatorActivity::class.java)
                .putExtra(EXTRA_CONSOLE, game.console.id)
                .putExtra(EXTRA_PATH, game.filePath)
                .putExtra(EXTRA_TITLE, game.title)
    }
}

private sealed interface EmulatorStatus {
    data object Loading : EmulatorStatus
    data object Running : EmulatorStatus
    data class Failed(val message: String) : EmulatorStatus
}
