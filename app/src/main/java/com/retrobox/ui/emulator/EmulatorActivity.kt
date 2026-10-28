package com.retrobox.ui.emulator

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.retrobox.data.Game
import com.retrobox.domain.Console
import com.retrobox.emulation.EmulatorException
import com.retrobox.emulation.EmulatorSession
import com.retrobox.emulation.NativeEmulator
import com.retrobox.emulation.RetroButton
import com.retrobox.ui.theme.RetroBoxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmulatorActivity : ComponentActivity() {

    private lateinit var session: EmulatorSession
    private var startJob: Job? = null

    private var status by mutableStateOf<EmulatorStatus>(EmulatorStatus.Loading)
    private var showMenu by mutableStateOf(false)
    private var showControls by mutableStateOf(true)
    private var fastForward by mutableStateOf(false)
    private var smoothFilter by mutableStateOf(true)
    private var fps by mutableStateOf(0.0)

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

        session = EmulatorSession(this)

        val console = Console.fromId(intent.getStringExtra(EXTRA_CONSOLE) ?: "")
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        status = runCatching {
            if (console == null) throw EmulatorException("Console desconhecido.")
            session.prepare(console, path, title)
            EmulatorStatus.Loading
        }.getOrElse { EmulatorStatus.Failed(it.message ?: "Falha ao preparar o jogo.") }

        setContent {
            RetroBoxTheme {
                EmulatorContent()
            }
        }
    }

    @Composable
    private fun EmulatorContent() {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> createSurfaceView(context) }
            )

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
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                }
            }

            if (showMenu) MenuOverlay()
        }
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
                Text("Carregando núcleo…", color = Color.White)
            }
        }
    }

    @Composable
    private fun ErrorOverlay(message: String) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Não foi possível iniciar", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(message, color = Color(0xFFFF8A80), fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { finish() }) { Text("Voltar") }
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
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Jogo pausado", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                    }
                    MenuAction(if (showControls) "Esconder controles" else "Mostrar controles") {
                        showControls = !showControls
                        showMenu = false
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
            Text(label, color = Color.White, modifier = Modifier.fillMaxWidth())
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun createSurfaceView(context: Context): SurfaceView {
        return SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (status is EmulatorStatus.Failed) return
                    status = EmulatorStatus.Loading
                    startJob = lifecycleScope.launch {
                        val error = withContext(Dispatchers.Default) { session.start(holder.surface) }
                        status = if (error.isEmpty()) EmulatorStatus.Running else EmulatorStatus.Failed(error)
                    }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    startJob?.cancel()
                    session.stop()
                    status = EmulatorStatus.Loading
                }
            })
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
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
