package com.memora.app.core.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 头像：先看内存，再看磁盘，最后按 QQ 头像规则补地址下载。
 * 头像地址经常带时效参数，所以只缓存文件本身，不缓存 URL。
 */
object AvatarStore {

    private val mem = LruCache<String, Bitmap>(80)

    fun candidateUrls(uin: String, raw: String): List<String> {
        val list = ArrayList<String>(3)
        if (raw.isNotBlank() && raw.startsWith("http")) list.add(raw)
        if (uin.isNotBlank()) {
            if (raw.isNotBlank() && raw.startsWith("//")) list.add("https:$raw")
            list.add("https://qlogo2.store.qq.com/qzone/$uin/$uin/100")
            list.add("https://q1.qlogo.cn/g?b=qq&nk=$uin&s=100")
        }
        return list.distinct()
    }

    suspend fun load(context: Context, uin: String, rawUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        val key = if (uin.isNotBlank()) uin else rawUrl.hashCode().toString()
        mem.get(key)?.let { return@withContext it }
        val dir = File(context.filesDir, "avatars").apply { mkdirs() }
        val file = File(dir, "$key.img")
        if (file.exists() && file.length() > 0) {
            decode(file)?.let { mem.put(key, it); return@withContext it }
        }
        for (url in candidateUrls(uin, rawUrl)) {
            val bitmap = runCatching { fetch(url) }.getOrNull() ?: continue
            runCatching { file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out) } }
            mem.put(key, bitmap)
            return@withContext bitmap
        }
        null
    }

    private fun decode(file: File): Bitmap? = runCatching {
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    private fun fetch(url: String): Bitmap? {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) MEMORA/1.2")
            .header("Referer", "https://user.qzone.qq.com/")
            .build()
        Net.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body.bytes()
            if (bytes.isEmpty()) return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
}
