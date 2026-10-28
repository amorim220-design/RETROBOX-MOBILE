package com.retrobox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.retrobox.data.Game
import com.retrobox.domain.Console
import com.retrobox.ui.navigation.MainScreen
import com.retrobox.ui.screens.home.HomeViewModel
import com.retrobox.ui.screens.home.HomeViewModelFactory
import com.retrobox.ui.theme.RetroBoxTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as RetroBoxApplication
        val viewModel = ViewModelProvider(
            this,
            HomeViewModelFactory(app.gameRepository, app.userPreferencesRepository)
        )[HomeViewModel::class.java]

        // Seed demo data if database is empty
        lifecycleScope.launch {
            val games = app.gameRepository.allGames.first()
            if (games.isEmpty()) {
                seedDemoData(app)
            }
        }

        setContent {
            RetroBoxTheme {
                MainScreen(viewModel)
            }
        }
    }

    private suspend fun seedDemoData(app: RetroBoxApplication) {
        val demoGames = listOf(
            Game(
                title = "A Lenda dos DragÃµes",
                console = Console.PS1,
                filePath = "demo://lenda_dragoes",
                coverUri = "android.resource://${packageName}/drawable/img_demo_ps1_rpg_1793198265037",
                isFavorite = true
            ),
            Game(
                title = "Corrida Suprema",
                console = Console.PS1,
                filePath = "demo://corrida_suprema",
                coverUri = "android.resource://${packageName}/drawable/img_demo_ps1_racing_1793198265609"
            ),
            Game(
                title = "Metroid Fusion",
                console = Console.GBA,
                filePath = "demo://metroid_fusion"
            ),
            Game(
                title = "Zelda: Ocarina of Time",
                console = Console.N64,
                filePath = "demo://zelda_oot"
            )
        )
        demoGames.forEach { app.gameRepository.addGame(it) }
    }
}
