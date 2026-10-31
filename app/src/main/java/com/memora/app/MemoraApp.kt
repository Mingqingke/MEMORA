package com.memora.app

import android.app.Application
import com.memora.app.core.Prefs
import com.memora.app.core.task.SyncService
import com.memora.app.core.task.TaskEngine
import com.memora.app.ui.AppState

class MemoraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 启动阶段任何一步失败都不能让应用直接打不开
        runCatching { Prefs.init(this) }
        runCatching { com.memora.app.core.Crash.install(this) }
        runCatching { SyncService.channel(this) }
        runCatching { AppState.bind(this) }
        if (!com.memora.app.core.Crash.inSafeMode(this)) {
            runCatching { TaskEngine.recoverInterrupted(this) }
        }
    }
}
