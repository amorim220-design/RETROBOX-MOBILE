package com.example.ui.screens.home

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.RetroLogo
import com.example.ui.screens.home.components.ConsoleSidebar
import com.example.ui.screens.home.components.GameGrid
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.SurfaceDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val selectedConsole by viewModel.selectedConsole.collectAsState()
    val games by viewModel.games.collectAsState()
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { uri ->
            viewModel.importGame(context, uri, selectedConsole)
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
            FloatingActionButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    filePickerLauncher.launch(intent)
                },
                containerColor = NeonBlue,
                contentColor = BackgroundDark,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "Importar Jogo")
            }
        }
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
                        onGameClick = { viewModel.launchGame(it) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) }
                    )
                }
            }
        }
    }
}

@Composable
fun HeaderSection(console: com.example.domain.Console) {
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
