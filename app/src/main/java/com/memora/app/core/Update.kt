package com.memora.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.memora.app.BuildConfig
import com.memora.app.core.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: List<String>,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
)

sealed interface UpdateCheck {
    data class Available(val info: UpdateInfo) : UpdateCheck
    data class Latest(val versionName: String) : UpdateCheck
    data class Failed(val reason: String) : UpdateCheck
    data class NotConfigured(val reason: String) : UpdateCheck
}

/**
 * 应用内更新：读 update.json → 比对版本 → 下载 → SHA-256 校验 → 交给系统安装。
 * 源可配置，多源逐个尝试，失败原因如实返回，不假装成功。
 */
object Updater {

    private val DEFAULT_SOURCES = listOf(
        "https://raw.githubusercontent.com/Mingqingke/MEMORA/main/update.json",
        "https://cdn.jsdelivr.net/gh/Mingqingke/MEMORA@main/update.json",
        "https://ghproxy.net/https://raw.githubusercontent.com/Mingqingke/MEMORA/main/update.json",
    )

    fun sources(context: Context): List<String> {
        val custom = Prefs.updateSource.value.trim()
        return if (custom.isNotBlank()) listOf(custom) + DEFAULT_SOURCES else DEFAULT_SOURCES
    }

    suspend fun check(context: Context): UpdateCheck = withContext(Dispatchers.IO) {
        val sources = sources(context)
        if (sources.isEmpty()) return@withContext UpdateCheck.NotConfigured("没有配置更新源")
        var lastReason = "没有可用的更新源"
        // 多个源并发查，谁先回来算谁，最后仍取版本号最大的那个
        val results = kotlinx.coroutines.coroutineScope {
            sources.map { url ->
                async {
                    val res = runCatching {
                        kotlinx.coroutines.withTimeout(6_000L) {
                            Net.get(url, "", "https://memora.app/", timeoutSeconds = 6)
                        }
                    }.getOrNull()
                    if (res == null || !res.ok || res.body.isBlank()) null
                    else runCatching { parse(res.body) }.getOrNull()
                }
            }.awaitAll()
        }
        val best = results.filterNotNull().maxByOrNull { it.versionCode }
        if (best == null) return@withContext UpdateCheck.Failed(lastReason)
        Prefs.setLastCheckAt(System.currentTimeMillis())
        if (best.versionCode <= BuildConfig.VERSION_CODE) UpdateCheck.Latest(best.versionName)
        else UpdateCheck.Available(best)
    }

    private fun parse(body: String): UpdateInfo {
        val o = JSONObject(body)
        val notes = o.optJSONArray("notes")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: listOfNotNull(o.optString("notes").takeIf { it.isNotBlank() })
        return UpdateInfo(
            versionCode = o.optInt("versionCode", 0),
            versionName = o.optString("versionName", ""),
            notes = notes,
            apkUrl = o.optString("apk", ""),
            size = o.optLong("size", 0),
            sha256 = o.optString("sha256", ""),
        )
    }

    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        if (info.apkUrl.isBlank()) return@withContext null
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, "memora-${info.versionName}.apk")
        val part = File(dir, "memora-${info.versionName}.part")
        runCatching {
            val request = Request.Builder().url(info.apkUrl)
                .header("User-Agent", "MEMORA/${BuildConfig.VERSION_NAME}")
                .build()
            Net.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val total = resp.body.contentLength().takeIf { it > 0 } ?: info.size
                resp.body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }.onFailure { Log.w("MemoraUpdate", "download failed", it); return@withContext null }
        if (!part.exists() || part.length() == 0L) return@withContext null
        if (info.size > 0 && part.length() != info.size) { part.delete(); return@withContext null }
        if (info.sha256.isNotBlank()) {
            val hash = sha256(part)
            if (!hash.equals(info.sha256, true)) { part.delete(); return@withContext null }
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
        target
    }

    private fun sha256(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    fun install(context: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    }.getOrDefault(false)
}
