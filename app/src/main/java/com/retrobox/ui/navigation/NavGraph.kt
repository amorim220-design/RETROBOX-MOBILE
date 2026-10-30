package com.retrobox.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.retrobox.ui.screens.bios.BiosScreen
import com.retrobox.ui.screens.bios.BiosViewModel
import com.retrobox.ui.screens.catalog.CatalogScreen
import com.retrobox.ui.screens.catalog.CatalogViewModel
import com.retrobox.ui.screens.home.HomeScreen
import com.retrobox.ui.screens.home.HomeViewModel
import com.retrobox.ui.screens.library.GameCollectionScreen
import com.retrobox.ui.screens.settings.SettingsScreen
import com.retrobox.ui.screens.settings.SettingsViewModel
import com.retrobox.ui.theme.NeonBlue

@Composable
fun MainScreen(homeViewModel: HomeViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val navController = rememberNavController()
    val screens = listOf(
        Screen.Home,
        Screen.Catalog,
        Screen.Library,
        Screen.Favorites,
        Screen.Settings
    )

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute != Screen.Bios.route) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = Color.White
                ) {
                    val currentDestination = backStackEntry?.destination
                    screens.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = null) },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = NeonBlue,
                                selectedTextColor = NeonBlue,
                                indicatorColor = NeonBlue.copy(alpha = 0.1f),
                                unselectedIconColor = Color.Gray,
                                unselectedTextColor = Color.Gray
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) { HomeScreen(homeViewModel) }

            composable(Screen.Catalog.route) {
                CatalogScreen(viewModel<CatalogViewModel>())
            }

            composable(Screen.Library.route) {
                GameCollectionScreen(
                    games = homeViewModel.allGames,
                    title = "BIBLIOTECA",
                    emptyMessage = "Nenhum jogo na biblioteca.\nImporte uma pasta ou baixe do catálogo.",
                    onToggleFavorite = homeViewModel::toggleFavorite,
                    onDelete = homeViewModel::deleteGame
                )
            }

            composable(Screen.Favorites.route) {
                GameCollectionScreen(
                    games = homeViewModel.favoriteGames,
                    title = "FAVORITOS",
                    emptyMessage = "Nenhum favorito ainda.\nToque na estrela de um jogo.",
                    onToggleFavorite = homeViewModel::toggleFavorite,
                    onDelete = homeViewModel::deleteGame
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel<SettingsViewModel>(),
                    onOpenBios = { navController.navigate(Screen.Bios.route) },
                    onOpenInput = {
                        val intent = android.content.Intent(
                            context,
                            com.retrobox.ui.screens.input.InputSettingsActivity::class.java
                        )
                        context.startActivity(intent)
                    }
                )
            }

            composable(Screen.Bios.route) {
                BiosScreen(
                    viewModel = viewModel<BiosViewModel>(),
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
fun PlaceholderScreen(name: String) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(text = "$name (Em breve)", style = MaterialTheme.typography.titleLarge)
        }
    }
}
