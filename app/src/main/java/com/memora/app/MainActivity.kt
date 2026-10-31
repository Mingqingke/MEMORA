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
        // 能正常进入界面就说明这次启动没崩，清掉连续崩溃计数
        window.decorView.postDelayed({
            runCatching { com.memora.app.core.Crash.clearStreak(this) }
        }, 3000)
        setContent {
            val mode by Prefs.themeMode.collectAsState()
            MemoraTheme(mode) { MemoraRoot() }
        }
    }
}
