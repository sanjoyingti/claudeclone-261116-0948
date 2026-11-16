package com.aibuilt.claudeclone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibuilt.claudeclone.data.AppStorage
import com.aibuilt.claudeclone.ui.ClaudeCloneApp
import com.aibuilt.claudeclone.ui.ClaudeCloneTheme
import com.aibuilt.claudeclone.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storage = AppStorage(applicationContext)
        setContent {
            ClaudeCloneTheme {
                val model: MainViewModel = viewModel(factory = MainViewModel.Factory(storage))
                ClaudeCloneApp(model)
            }
        }
    }
}
