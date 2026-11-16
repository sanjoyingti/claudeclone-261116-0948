package com.aibuilt.claudeclone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aibuilt.claudeclone.viewmodel.MainViewModel

private val ClaudeLight = lightColorScheme(
    primary = Color(0xFFD97757),
    onPrimary = Color.White,
    secondary = Color(0xFF526354),
    secondaryContainer = Color(0xFFEAE8E0),
    onSecondaryContainer = Color(0xFF191919),
    background = Color(0xFFF5F4EF),
    surface = Color(0xFFFAF9F5),
    onSurface = Color(0xFF191919),
    onSurfaceVariant = Color(0xFF6B6A65),
    surfaceVariant = Color(0xFFE2DFD5),
    error = Color(0xFFB3261E)
)

private val ClaudeDark = darkColorScheme(
    primary = Color(0xFFE59A7B),
    onPrimary = Color(0xFF2B1710),
    secondary = Color(0xFFB6C8B6),
    secondaryContainer = Color(0xFF2F2E2B),
    onSecondaryContainer = Color(0xFFF1F0EB),
    background = Color(0xFF1A1918),
    surface = Color(0xFF262523),
    onSurface = Color(0xFFF1F0EB),
    onSurfaceVariant = Color(0xFFB8B6AF),
    surfaceVariant = Color(0xFF393834),
    error = Color(0xFFFFB4AB)
)

@Composable
fun ClaudeCloneTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) ClaudeDark else ClaudeLight, content = content)
}

@Composable
fun ClaudeCloneApp(viewModel: MainViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "chat") {
        composable("chat") {
            ChatScreen(viewModel = viewModel, onSettings = { nav.navigate("settings") })
        }
        composable("settings") {
            SettingsScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
        }
    }
}
