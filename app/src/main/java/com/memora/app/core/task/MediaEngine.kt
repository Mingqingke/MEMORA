package com.memora.app.core.task

import android.content.Context
import android.util.Log
import com.memora.app.core.MediaItem
import com.memora.app.core.db.Db
import com.memora.app.core.net.Net
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 媒体下载：支持 Range 续传，落盘前写临时文件，成功后原子改名并算 SHA-256。
 * 不支持 Range 的服务器不会拼接，直接从头重新下载，避免生成损坏文件。
 */
object MediaEngine {

    private val queued = AtomicInteger(0)
    private val downloaded = AtomicInteger(0)
    val queuedCount: Int get() = queued.get()
    val doneCount: Int get() = downloaded.get()

    fun queueCount(n: Int) { queued.addAndGet(n) }

    fun dir(context: Context): File = File(context.filesDir, "archive/media").apply { mkdirs() }

    data class Result(val ok: Int, val failed: Int)

    fun downloadPending(context: Context, db: Db, taskId: String): Result {
        var ok = 0
        var failed = 0
        val attempted = HashSet<String>()
        var guard = 0
        // 同一轮里失败的项目不重复尝试：否则状态为「失败」的记录会让循环空转
        while (guard++ < 400) {
            val batch = db.pendingMedia(30).filter { it.id !in attempted }
            if (batch.isEmpty()) break
            for (item in batch) {
                if (!TaskEngine.isRunning(taskId)) break
                attempted.add(item.id)
                val success = runCatching { download(context, db, item) }.getOrElse {
                    Log.w("MemoraMedia", "download failed", it); false
                }
                if (success) { ok++; downloaded.incrementAndGet() } else failed++
            }
        }
        // 失败项自动再试一轮（链接临时失效、网络抖动很常见），仍失败才保留为待处理
        if (failed > 0) {
            attempted.forEach { db.updateMediaState(it, 0) }
            var retry = 0
            for (id in attempted.toList()) {
                if (!TaskEngine.isRunning(taskId) || retry >= 60) break
                val item = db.pendingMedia(1).firstOrNull { it.id == id } ?: continue
                retry++
                if (runCatching { download(context, db, item) }.getOrDefault(false)) {
                    ok++; failed--; downloaded.incrementAndGet()
                }
            }
        }
        return Result(ok, failed)
    }

    fun download(context: Context, db: Db, item: MediaItem): Boolean {
        if (item.url.isBlank() || !item.url.startsWith("http")) {
            db.updateMediaState(item.id, 4)
            return false
        }
        val ext = item.url.substringBefore('?').substringAfterLast('.', "jpg").take(5)
            .filter { it.isLetterOrDigit() }.ifBlank { "jpg" }
        val target = File(dir(context), "${item.id}.$ext")
        val part = File(dir(context), "${item.id}.part")
        var existing = if (part.exists()) part.length() else 0L
        if (target.exists() && target.length() > 0) {
            val hash = sha256(target)
            db.updateMediaState(item.id, 2, target.absolutePath, target.length(), target.length(), hash)
            return true
        }

        db.updateMediaState(item.id, 1, bytes = existing)
        val builder = Request.Builder().url(item.url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) MEMORA/1.0")
            .header("Referer", "https://user.qzone.qq.com/")
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        val resp = try {
            Net.client.newCall(builder.build()).execute()
        } catch (t: Throwable) {
            db.updateMediaState(item.id, 3, bytes = existing)
            return false
        }
        resp.use { r ->
            if (r.code == 416 && target.exists()) {
                val hash = sha256(target)
                db.updateMediaState(item.id, 2, target.absolutePath, target.length(), target.length(), hash)
                return true
            }
            if (r.code !in 200..299) {
                db.updateMediaState(item.id, if (r.code == 403 || r.code == 404) 4 else 3, bytes = existing)
                return false
            }
            val append = r.code == 206 && existing > 0
            if (!append) existing = 0
            val body = r.body
            val total = body.contentLength().let { if (it > 0) it + existing else 0L }
            RandomAccessFile(part, "rw").use { raf ->
                if (!append) raf.setLength(0)
                raf.seek(existing)
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        written += n
                        if (written % (512 * 1024) < 64 * 1024) {
                            db.updateMediaState(item.id, 1, bytes = existing + written)
                        }
                    }
                }
            }
            val length = part.length()
            if (total > 0 && length < total) {
                // 服务端给了长度但没下完：保留 .part，下次续传
                db.updateMediaState(item.id, 3, bytes = length)
                return false
            }
            if (length <= 0) { db.updateMediaState(item.id, 3); return false }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true); part.delete()
            }
            val hash = sha256(target)
            db.updateMediaState(item.id, 2, target.absolutePath, target.length(), total.takeIf { it > 0 } ?: length, hash)
            return true
        }
    }

    fun sha256(file: File): String = runCatching {
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
}
