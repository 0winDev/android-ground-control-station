package com.owindev.gcs

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.owindev.gcs.core.designsystem.theme.GcsTheme
import com.owindev.gcs.feature.hud.HudScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // GcsTheme is always dark (night palette), so system bar icons must be light regardless of the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            GcsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HudScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
