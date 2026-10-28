package com.retrobox.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.retrobox.ui.screens.home.HomeScreen
import com.retrobox.ui.screens.home.HomeViewModel
import com.retrobox.ui.theme.NeonBlue

@Composable
fun MainScreen(homeViewModel: HomeViewModel) {
    val navController = rememberNavController()
    val screens = listOf(
        Screen.Home,
        Screen.Library,
        Screen.Favorites,
        Screen.Settings
    )

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = Color.White
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
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
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) { HomeScreen(homeViewModel) }
            composable(Screen.Library.route) { PlaceholderScreen("Biblioteca") }
            composable(Screen.Favorites.route) { PlaceholderScreen("Favoritos") }
            composable(Screen.Settings.route) { PlaceholderScreen("ConfiguraÃ§Ãµes") }
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
