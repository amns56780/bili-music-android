package com.bilimusic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.bilimusic.app.ui.navigation.BiliMusicApp
import com.bilimusic.app.ui.theme.BiliMusicTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 单 Activity 架构：所有页面都在 Compose 里，Activity 只负责 edge-to-edge 与主题。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BiliMusicTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    BiliMusicApp()
                }
            }
        }
    }
}
