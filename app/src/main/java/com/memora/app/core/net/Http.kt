package com.memora.app.core.net

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class HttpResult(
    val code: Int,
    val body: String,
    val latencyMs: Long,
    val error: String = "",
    val setCookie: List<String> = emptyList(),
    val etag: String = "",
) {
    val ok: Boolean get() = error.isBlank() && code in 200..299
}

/**
 * 唯一出口。所有 QQ 请求都走这里：不阻塞主线程、统一超时、统一 UA、
 * 失败不吞异常，原样交给上层判定（HTTP 200 不等于解析成功）。
 */
object Net {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; MI 9) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/116.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    fun get(
        url: String,
        cookie: String,
        referer: String = "https://user.qzone.qq.com/",
        timeoutSeconds: Int = 0,
    ): HttpResult {
        val started = System.currentTimeMillis()
        val callClient = if (timeoutSeconds > 0) {
            client.newBuilder().callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS).build()
        } else client
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/json,application/xhtml+xml,*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", referer)
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        return try {
            callClient.newCall(builder.build()).execute().use { resp ->
                val text = resp.body.string()
                HttpResult(
                    code = resp.code, body = text,
                    latencyMs = System.currentTimeMillis() - started,
                    setCookie = resp.headers.values("Set-Cookie"),
                )
            }
        } catch (t: Throwable) {
            HttpResult(0, "", System.currentTimeMillis() - started, t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }

    /** 带自定义头的 GET；304 时不读 body，交给调用方用缓存。 */
    fun getWithHeaders(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Int = 8,
    ): HttpResult {
        val started = System.currentTimeMillis()
        val builder = Request.Builder().url(url)
            .header("User-Agent", "MEMORA/${com.memora.app.BuildConfig.VERSION_NAME}")
            .header("Accept", "application/vnd.github+json, application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val callClient = client.newBuilder().callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS).build()
        return try {
            callClient.newCall(builder.build()).execute().use { resp ->
                val body = if (resp.code == 304) "" else resp.body.string()
                HttpResult(
                    code = resp.code, body = body,
                    latencyMs = System.currentTimeMillis() - started,
                    setCookie = resp.headers.values("Set-Cookie"),
                    etag = resp.header("ETag").orEmpty(),
                )
            }
        } catch (t: Throwable) {
            HttpResult(0, "", System.currentTimeMillis() - started, t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }

    data class BinaryResult(val code: Int, val bytes: ByteArray?, val setCookie: List<String>)

    /** 二进制响应（二维码图片），同时把 Set-Cookie 带回来，避免同一个二维码被请求两次。 */
    fun getBinary(url: String, cookie: String, referer: String = "https://xui.ptlogin2.qq.com/"): BinaryResult {
        val builder = Request.Builder().url(url).header("User-Agent", UA).header("Referer", referer)
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        return runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                BinaryResult(resp.code, if (resp.isSuccessful) resp.body.bytes() else null, resp.headers.values("Set-Cookie"))
            }
        }.getOrDefault(BinaryResult(0, null, emptyList()))
    }

    /** 二进制下载（二维码图片等），不做字符集转换。 */
    fun getBytes(url: String, cookie: String, referer: String = "https://user.qzone.qq.com/"): ByteArray? {
        val builder = Request.Builder().url(url).header("User-Agent", UA).header("Referer", referer)
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        return runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body.bytes()
            }
        }.getOrNull()
    }

    /** JSONP → JSON；不是 JSONP 原样返回。 */
    fun unwrapJsonp(body: String): String? {
        val t = body.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("{")) return t
        val open = t.indexOf('(')
        val close = t.lastIndexOf(')')
        if (open in 1..40 && close > open) {
            val inner = t.substring(open + 1, close).trim()
            if (inner.startsWith("{") || inner.startsWith("[")) return inner
        }
        return null
    }

    fun json(body: String): JSONObject? = runCatching {
        val j = unwrapJsonp(body) ?: return null
        JSONObject(j)
    }.getOrNull()

    /**
     * 合并 Set-Cookie：QQ 的 p_skey 往往不是登录那一刻给的，
     * 而是在访问 user.qzone.qq.com/{uin} 时下发，必须回写进会话，否则 g_tk 全是错的。
     */
    fun mergeCookies(existing: String, setCookies: List<String>): String {
        if (setCookies.isEmpty()) return existing
        val jar = LinkedHashMap<String, String>()
        existing.split(";").forEach { kv ->
            val k = kv.substringBefore("=").trim()
            val v = kv.substringAfter("=", "").trim()
            if (k.isNotBlank() && v.isNotBlank()) jar[k] = v
        }
        setCookies.forEach { raw ->
            val first = raw.substringBefore(";").trim()
            val k = first.substringBefore("=").trim()
            val v = first.substringAfter("=", "").trim()
            if (k.isNotBlank() && v.isNotBlank() && !v.equals("deleted", true)) jar[k] = v
        }
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** g_tk = hash33(skey)，QQ 网页接口的通用校验参数。 */
    fun gtk(skey: String): String {
        if (skey.isBlank()) return "0"
        var hash = 5381
        for (c in skey) {
            hash += (hash shl 5) + c.code
            hash = hash and 0x7FFFFFFF
        }
        return hash.toString()
    }

    /** 诊断样本：截断保存，不含 Cookie，供接口结构变化时排查。 */
    fun saveSample(context: Context, sourceId: String, body: String) {
        runCatching {
            val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
            val f = File(dir, "$sourceId-${System.currentTimeMillis()}.txt")
            f.writeText(body.take(240_000))
            val files = dir.listFiles()?.sortedByDescending { it.name } ?: return
            files.drop(24).forEach { it.delete() }
        }
    }
}
