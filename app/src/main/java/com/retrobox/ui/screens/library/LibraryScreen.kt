package com.retrobox.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gamepad
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
import com.retrobox.data.Game
import com.retrobox.ui.emulator.EmulatorActivity
import com.retrobox.ui.screens.home.components.GameGrid
import com.retrobox.ui.theme.BackgroundDark
import com.retrobox.ui.theme.NeonBlue
import com.retrobox.ui.theme.SurfaceDark
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameCollectionScreen(
    games: StateFlow<List<Game>>,
    title: String,
    emptyMessage: String,
    onToggleFavorite: (Game) -> Unit,
    onDelete: (Game, Boolean) -> Unit = { _, _ -> }
) {
    val list by games.collectAsState()
    val context = LocalContext.current

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(BackgroundDark, SurfaceDark)))
                .padding(padding)
        ) {
            if (list.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Gamepad,
                        contentDescription = null,
                        tint = NeonBlue.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        emptyMessage,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )
                }
            } else {
                GameGrid(
                    games = list,
                    onGameClick = { game -> context.startActivity(EmulatorActivity.intent(context, game)) },
                    onToggleFavorite = onToggleFavorite,
                    onDelete = onDelete
                )
            }
        }
    }
}
