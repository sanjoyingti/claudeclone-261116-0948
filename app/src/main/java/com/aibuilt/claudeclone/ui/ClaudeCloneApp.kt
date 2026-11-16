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
    primary = Color(0xFFB45737),
    onPrimary = Color.White,
    secondary = Color(0xFF526354),
    background = Color(0xFFF8F5F0),
    surface = Color(0xFFFFFCF7),
    error = Color(0xFFB3261E)
)
private val ClaudeDark = darkColorScheme(
    primary = Color(0xFFE59A7B),
    secondary = Color(0xFFB6C8B6),
    background = Color(0xFF171614),
    surface = Color(0xFF22211F),
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
