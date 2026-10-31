package com.memora.app.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃记录：把最近一次未捕获异常写到应用私有目录，
 * 诊断页可以直接看到，不需要用户去抓 logcat。
 */
object Crash {

    private const val FILE = "last_crash.txt"

    private const val PREF = "memora_prefs"
    private const val KEY_STREAK = "crash_streak"

    /** 连续两次启动即崩时进入安全模式：跳过自动任务与自动更新。 */
    fun inSafeMode(context: Context): Boolean = streak(context) >= 2

    fun streak(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(KEY_STREAK, 0)

    fun clearStreak(context: Context) {
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY_STREAK, 0).apply()
    }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sp = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                sp.edit().putInt(KEY_STREAK, sp.getInt(KEY_STREAK, 0) + 1).apply()
            }
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(app.filesDir, FILE).writeText(
                    "time=${System.currentTimeMillis()}\nthread=${thread.name}\n\n" + sw.toString().take(16_000)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 捕获但不崩溃的异常也记下来，方便诊断页看到。 */
    fun record(context: Context, error: Throwable) {
        runCatching {
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            File(context.applicationContext.filesDir, FILE).writeText(
                "time=${System.currentTimeMillis()}\nthread=caught\n\n" + sw.toString().take(16_000)
            )
        }
    }

    fun last(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
