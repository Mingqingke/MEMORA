package com.memora.app

import android.app.Application
import com.memora.app.core.Prefs
import com.memora.app.core.task.SyncService
import com.memora.app.core.task.TaskEngine
import com.memora.app.ui.AppState

class MemoraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        com.memora.app.core.Crash.install(this)
        AppState.bind(this)
        SyncService.channel(this)
        TaskEngine.recoverInterrupted(this)
    }
}
