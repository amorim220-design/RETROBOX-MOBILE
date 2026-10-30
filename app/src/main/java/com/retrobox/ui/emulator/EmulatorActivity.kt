package com.retrobox.ui.emulator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.KeyEvent
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
import com.retrobox.emulation.EmulatorException
import com.retrobox.emulation.EmulatorSession
import com.retrobox.emulation.ErrorReporter
import com.retrobox.emulation.NativeEmulator
import com.retrobox.emulation.RetroButton
import com.retrobox.ui.screens.bios.BiosScreen
import com.retrobox.ui.screens.bios.BiosViewModel
import com.retrobox.ui.theme.RetroBoxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmulatorActivity : ComponentActivity() {

    private lateinit var session: EmulatorSession
    private lateinit var preferences: UserPreferencesRepository
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        preferences = (application as RetroBoxApplication).userPreferencesRepository
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
                    if (showControls && !showMenu) {
                        TouchControls(
                            onPress = { id, pressed ->
                                NativeEmulator.nativeSetButton(0, id, pressed)
                            }
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
                    MenuAction("Salvar estado") {
                        val ok = session.saveState()
                        showMenu = false
                        toast(if (ok) "Estado salvo" else "Este núcleo não suporta save state")
                    }
                    MenuAction("Carregar estado") {
                        val ok = session.loadState()
                        showMenu = false
                        toast(if (ok) "Estado carregado" else "Nenhum estado salvo")
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
                    pendingSurface?.release()
                    pendingSurface = null
                    pendingTexture = null
                    session.stop()
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
        val retroId = gamepadKeys[event.keyCode]
        if (retroId != null && status is EmulatorStatus.Running) {
            NativeEmulator.nativeSetButton(0, retroId, event.action == KeyEvent.ACTION_DOWN)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        NativeEmulator.nativeSetFastForward(false)
        // autosave ao sair do app (não em rotação nem com o menu aberto)
        if (autoSaveOnExit && session.started && !isChangingConfigurations) {
            runCatching { session.saveState() }
        }
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
