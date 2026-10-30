package com.retrobox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.retrobox.ui.navigation.MainScreen
import com.retrobox.ui.screens.home.HomeViewModel
import com.retrobox.ui.screens.home.HomeViewModelFactory
import com.retrobox.ui.theme.RetroBoxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as RetroBoxApplication
        val viewModel = ViewModelProvider(
            this,
            HomeViewModelFactory(app.gameRepository, app.userPreferencesRepository)
        )[HomeViewModel::class.java]

        // A biblioteca não nasce mais de dados de demonstração: ela vem da
        // importação do usuário ou do catálogo (Application já carrega o
        // catálogo em cache/embutido antes da primeira tela).
        setContent {
            RetroBoxTheme {
                MainScreen(viewModel)
            }
        }
    }
}
