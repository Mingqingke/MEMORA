package com.memora.app.core.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.memora.app.R
import com.memora.app.core.db.Db

/** 前台服务：只负责让长时间采集不被系统回收，进度来自任务引擎。 */
class SyncService : Service() {

    companion object {
        private const val CHANNEL = "memora_sync"
        private const val NOTIFY_ID = 4101
        private const val EXTRA_TASK = "task"
        private const val EXTRA_LABEL = "label"

        fun channel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "档案同步", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "采集与下载进度"
                        setShowBadge(false)
                    }
                )
            }
        }

        fun ensure(context: Context, taskId: String, label: String) {
            channel(context)
            val intent = Intent(context, SyncService::class.java)
                .putExtra(EXTRA_TASK, taskId)
                .putExtra(EXTRA_LABEL, label)
            runCatching { context.startForegroundService(intent) }
        }

        fun stopIfIdle(context: Context) {
            if (TaskEngine.runningIds.value.isEmpty()) {
                runCatching { context.stopService(Intent(context, SyncService::class.java)) }
            }
        }

        fun notifyDone(context: Context, label: String, inserted: Int) {
            channel(context)
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("$label 已完成")
                .setContentText("新增 $inserted 条记录")
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(NOTIFY_ID + 1, n) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK).orEmpty()
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "档案同步" }
        val notification = build(label)
        startForeground(NOTIFY_ID, notification)
        if (taskId.isNotBlank()) {
            val nm = getSystemService(NotificationManager::class.java)
            // 进度文案由引擎写入，这里做一次轻量同步
            val text = TaskEngine.progress.value[taskId]
            if (!text.isNullOrBlank()) {
                runCatching {
                    nm.notify(
                        NOTIFY_ID,
                        NotificationCompat.Builder(this, CHANNEL)
                            .setSmallIcon(R.drawable.ic_launcher_fg)
                            .setContentTitle(label)
                            .setContentText(text)
                            .setOngoing(true)
                            .build(),
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(label: String): Notification {
        val db = Db.get(this)
        val running = TaskEngine.runningIds.value.size
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(label)
            .setContentText(if (running > 1) "共 $running 个任务进行中" else "正在采集 可随时暂停")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }
}
