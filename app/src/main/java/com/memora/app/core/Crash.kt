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

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
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

    fun last(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
