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
    data class Failed(val reason: String, val detail: String = "") : UpdateCheck
    data class NotConfigured(val reason: String) : UpdateCheck
}

/**
 * 应用内更新：读 update.json → 比对版本 → 下载 → SHA-256 校验 → 交给系统安装。
 * 源可配置，多源逐个尝试，失败原因如实返回，不假装成功。
 */
object Updater {

    private val DEFAULT_SOURCES = listOf(
        "https://ghproxy.net/https://raw.githubusercontent.com/Mingqingke/MEMORA/main/update.json",
        "https://raw.githubusercontent.com/Mingqingke/MEMORA/main/update.json",
        "https://cdn.jsdelivr.net/gh/Mingqingke/MEMORA@main/update.json",
    )

    private const val RELEASE_API = "https://api.github.com/repos/Mingqingke/MEMORA/releases/latest"
    private const val PER_SOURCE_TIMEOUT_MS = 3_500L
    private const val TOTAL_TIMEOUT_MS = 6_000L

    fun sources(context: Context): List<String> {
        val custom = Prefs.updateSource.value.trim()
        return if (custom.isNotBlank()) listOf(custom) + DEFAULT_SOURCES else DEFAULT_SOURCES
    }

    /** 版本号解析：v1.2.42 / 1.2.42 -> 10242，用于缺少 versionCode 时兜底。 */
    fun parseVersionCode(name: String): Int {
        val m = Regex("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").find(name.trim().removePrefix("v")) ?: return 0
        val a = m.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
        val b = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        val c = m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
        return a * 10000 + b * 100 + c
    }

    private data class Fetched(
        val source: String,
        val code: Int,
        val ms: Long,
        val versionName: String = "",
        val versionCode: Int = 0,
        val apkUrl: String = "",
        val size: Long = 0,
        val sha: String = "",
        val notes: List<String> = emptyList(),
        val error: String = "",
    )

    @Suppress("UNUSED_PARAMETER")
    private fun etagKey(url: String) = "etag_" + url.hashCode()

    /** GitHub 正式 Release：唯一的权威版本来源，失败时才回落 JSON。 */
    private fun fetchRelease(context: Context): Fetched {
        val sp = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
        val key = etagKey(RELEASE_API)
        val cachedEtag = sp.getString(key + "_etag", "").orEmpty()
        val cachedBody = sp.getString(key + "_body", "").orEmpty()
        val headers = if (cachedEtag.isNotBlank()) mapOf("If-None-Match" to cachedEtag) else emptyMap()
        val started = System.currentTimeMillis()
        val res = Net.getWithHeaders(RELEASE_API, headers, 6)
        val ms = System.currentTimeMillis() - started
        if (res.code == 304 && cachedBody.isNotBlank()) {
            return parseRelease(cachedBody, "release-api(cache)", 304, ms)
        }
        if (!res.ok) {
            val reason = when (res.code) {
                403, 429 -> "GitHub 限流"
                404 -> "Release 不存在"
                0 -> "网络不可用"
                else -> "HTTP ${res.code}"
            }
            return Fetched("release-api", res.code, ms, error = reason)
        }
        if (res.etag.isNotBlank()) {
            sp.edit().putString(key + "_etag", res.etag).putString(key + "_body", res.body).apply()
        }
        return parseRelease(res.body, "release-api", res.code, ms)
    }

    private fun parseRelease(body: String, source: String, code: Int, ms: Long): Fetched {
        val o = runCatching { JSONObject(body) }.getOrNull()
            ?: return Fetched(source, code, ms, error = "更新信息格式不正确")
        if (o.optBoolean("draft", false)) return Fetched(source, code, ms, error = "最新发布仍是草稿")
        if (o.optBoolean("prerelease", false)) return Fetched(source, code, ms, error = "只找到预发布版本")
        val tag = o.optString("tag_name", "")
        val assets = o.optJSONArray("assets")
        val apk = assets?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
        }
        val bodyText = o.optString("body", "")
        // 和 FriendSim 一致：versionCode 直接写进 Release 正文，避免依赖 update.json 的缓存
        val codeInBody = Regex("(?m)^\\s*code\\s*=\\s*(\\d+)")
            .find(bodyText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return Fetched(
            source = source,
            code = code,
            ms = ms,
            versionName = o.optString("name", tag).ifBlank { tag },
            versionCode = if (codeInBody > 0) codeInBody else parseVersionCode(tag),
            apkUrl = apk?.optString("browser_download_url", "").orEmpty(),
            size = apk?.optLong("size", 0) ?: 0,
            sha = apk?.optString("digest", "").orEmpty().removePrefix("sha256:"),
            notes = bodyText.lines().map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("code=") && !it.startsWith("display=") }
                .take(8),
        )
    }

    private fun fetchJson(context: Context, url: String): Fetched {
        val started = System.currentTimeMillis()
        val res = runCatching { Net.get(url, "", "https://memora.app/", timeoutSeconds = 4) }.getOrNull()
        val ms = System.currentTimeMillis() - started
        if (res == null || !res.ok || res.body.isBlank()) {
            return Fetched(url, res?.code ?: 0, ms, error = if (res == null) "网络不可用" else "HTTP ${res.code}")
        }
        return runCatching {
            val o = JSONObject(res.body)
            val notes = o.optJSONArray("notes")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList()
            val name = o.optString("versionName", "")
            Fetched(
                source = url, code = res.code, ms = ms,
                versionName = name,
                versionCode = o.optInt("versionCode", parseVersionCode(name)),
                apkUrl = o.optString("apk", ""),
                size = o.optLong("size", 0),
                sha = o.optString("sha256", ""),
                notes = notes,
            )
        }.getOrElse { Fetched(url, res.code, ms, error = "更新信息格式不正确") }
    }

    private fun recordDiagnostics(results: List<Fetched>, chosen: UpdateInfo?, verdict: String) {
        val line = buildString {
            append(java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date()))
            append(" 本地 ").append(BuildConfig.VERSION_NAME).append("/").append(BuildConfig.VERSION_CODE)
            results.forEach { r ->
                append("\n· ").append(
                    when {
                        r.source.startsWith("release") -> "Release"
                        r.source.contains("jsdelivr") -> "jsDelivr"
                        r.source.contains("ghproxy") -> "ghproxy"
                        r.source.contains("raw.githubusercontent") -> "raw"
                        else -> "自定义"
                    }
                )
                append(" ").append(r.code).append(" ").append(r.ms).append("ms")
                if (r.versionCode > 0) append(" → ").append(r.versionName).append("(").append(r.versionCode).append(")")
                if (r.error.isNotBlank()) append(" ✕ ").append(r.error)
            }
            if (chosen != null) append("\n结论 ").append(verdict).append(" ").append(chosen.versionName)
        }
        Prefs.setUpdateDiag(line)
    }

    suspend fun check(context: Context): UpdateCheck = withContext(Dispatchers.IO) {
        val urls = sources(context)
        val results = kotlinx.coroutines.coroutineScope {
            val jobs = buildList {
                add(async { runCatching { fetchRelease(context) }.getOrElse { Fetched("release-api", 0, 0, error = "解析失败") } })
                urls.forEach { url -> add(async { runCatching { fetchJson(context, url) }.getOrElse { Fetched(url, 0, 0, error = "请求失败") } }) }
            }
            kotlinx.coroutines.withTimeoutOrNull(TOTAL_TIMEOUT_MS) { jobs.awaitAll() } ?: emptyList()
        }
        val release = results.firstOrNull { it.source.startsWith("release") }
        val jsons = results.filter { !it.source.startsWith("release") && it.versionCode > 0 }
        val bestJson = jsons.maxByOrNull { it.versionCode }

        // versionCode 以清单为准；清单缺失时退回 tag 解析
        val code = bestJson?.versionCode?.takeIf { it > 0 }
            ?: release?.versionCode?.takeIf { it > 0 }
            ?: 0
        val name = bestJson?.versionName?.takeIf { it.isNotBlank() }
            ?: release?.versionName.orEmpty()
        val apk = release?.apkUrl?.takeIf { it.startsWith("http") }
            ?: bestJson?.apkUrl.orEmpty()
        val sha = release?.sha?.takeIf { it.length == 64 } ?: bestJson?.sha.orEmpty()
        val size = release?.size?.takeIf { it > 0 } ?: bestJson?.size ?: 0
        val notes = bestJson?.notes?.takeIf { it.isNotEmpty() } ?: release?.notes ?: emptyList()

        if (code <= 0 || name.isBlank()) {
            val reason = results.firstOrNull { it.error.isNotBlank() }?.error ?: "没有可用的更新源"
            recordDiagnostics(results, null, "失败")
            return@withContext UpdateCheck.Failed(reason, results.joinToString("；") { it.source + ":" + (it.error.ifBlank { it.code.toString() }) })
        }
        val info = UpdateInfo(code, name, notes, apk, size, sha)
        Prefs.setLastCheckAt(System.currentTimeMillis())
        if (code <= BuildConfig.VERSION_CODE) {
            recordDiagnostics(results, info, "已是最新")
            UpdateCheck.Latest(name)
        } else {
            recordDiagnostics(results, info, "发现更新")
            UpdateCheck.Available(info)
        }
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
