package com.retrobox.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home : Screen("home", "InÃ­cio", Icons.Default.Home)
    object Library : Screen("library", "Biblioteca", Icons.Default.LibraryBooks)
    object Favorites : Screen("favorites", "Favoritos", Icons.Default.Favorite)
    object Settings : Screen("settings", "ConfiguraÃ§Ãµes", Icons.Default.Settings)
}
