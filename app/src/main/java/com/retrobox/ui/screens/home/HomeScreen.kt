package com.retrobox.ui.screens.home

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrobox.ui.components.RetroLogo
import com.retrobox.ui.emulator.EmulatorActivity
import com.retrobox.ui.screens.home.components.ConsoleSidebar
import com.retrobox.ui.screens.home.components.GameGrid
import com.retrobox.ui.theme.BackgroundDark
import com.retrobox.ui.theme.NeonBlue
import com.retrobox.ui.theme.SurfaceDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val selectedConsole by viewModel.selectedConsole.collectAsState()
    val games by viewModel.games.collectAsState()
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importGame(context, uri, selectedConsole)
        }
    }

    // Importa uma pasta inteira (uma subpasta por console)
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importFolder(context, uri)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        // importa automaticamente o que estiver na pasta do app
        viewModel.importFromAppFolder(context)
    }
    LaunchedEffect(Unit) {
        viewModel.refreshMissingCovers(context)
    }
    LaunchedEffect(Unit) {
        viewModel.message.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { RetroLogo() },
                actions = {
                    IconButton(
                        onClick = { /* Search */ },
                        modifier = Modifier.background(Color.White.copy(alpha = 0.05f), CircleShape)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Pesquisar", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundDark
                )
            )
        },
        floatingActionButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FloatingActionButton(
                    onClick = { folderPickerLauncher.launch(null) },
                    containerColor = SurfaceDark,
                    contentColor = NeonBlue,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "Importar pasta")
                }
                FloatingActionButton(
                    onClick = {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                    containerColor = NeonBlue,
                    contentColor = BackgroundDark,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Importar Jogo")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(BackgroundDark, SurfaceDark)
                    )
                )
                .padding(padding)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                ConsoleSidebar(
                    selectedConsole = selectedConsole,
                    onConsoleSelected = { viewModel.selectConsole(it) }
                )

                Column(modifier = Modifier.fillMaxSize()) {
                    HeaderSection(selectedConsole)
                    GameGrid(
                        games = games,
                        onGameClick = { game ->
                            viewModel.launchGame(game)
                            context.startActivity(EmulatorActivity.intent(context, game))
                        },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onDelete = { game, deleteFile -> viewModel.deleteGame(game, deleteFile) }
                    )
                }
            }
        }
    }
}

@Composable
fun HeaderSection(console: com.retrobox.domain.Console) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Icon placeholder for console
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(NeonBlue.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Gamepad,
                    contentDescription = null,
                    tint = NeonBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "JOGOS DO ${console.displayName}",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                ),
                color = Color.White
            )
        }
        
        IconButton(
            onClick = { /* Filter */ },
            modifier = Modifier.background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
        ) {
            Icon(Icons.Default.FilterList, contentDescription = "Filtrar", tint = NeonBlue)
        }
    }
}
