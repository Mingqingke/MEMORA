package com.memora.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.memora.app.core.Prefs
import com.memora.app.ui.MemoraRoot
import com.memora.app.ui.theme.MemoraTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mode by Prefs.themeMode.collectAsState()
            MemoraTheme(mode) { MemoraRoot() }
        }
    }
}
