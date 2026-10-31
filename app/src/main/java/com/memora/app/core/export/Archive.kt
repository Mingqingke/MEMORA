package com.memora.app.core.export

import android.content.Context
import android.net.Uri
import com.memora.app.core.db.Db
import com.memora.app.core.source.CommentRec
import com.memora.app.core.source.FriendRec
import com.memora.app.core.source.MomentRec
import com.memora.app.core.recovery.Ingest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportSummary(
    val moments: Int,
    val friends: Int,
    val comments: Int,
    val activities: Int,
    val mediaFiles: Int,
    val bytes: Long,
    val path: String,
)

/**
 * 导出：JSON 明细 + 离线 HTML 浏览器 + 媒体副本，全部打包进 ZIP，
 * manifest 记录格式版本、数量与每个文件的 SHA-256，便于迁移时校验。
 * Cookie / 会话凭证永远不进入导出包。
 */
object Exporter {

    fun exportZip(context: Context, db: Db, accountId: String, out: OutputStream): ExportSummary {
        val account = db.account(accountId)
        val friends = db.friends(accountId)
        val moments = db.moments(accountId, limit = 100_000)
        val albums = db.albums(accountId)
        val now = System.currentTimeMillis()
        var comments = 0
        var activities = 0
        var mediaFiles = 0
        var bytes = 0L
        val hashes = LinkedHashMap<String, String>()

        ZipOutputStream(out).use { zip ->
            fun entry(name: String, body: String) {
                val data = body.toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
                hashes[name] = sha256(data)
            }

            val momentsJson = buildJsonArray {
                for (m in moments) {
                    val media = db.media(m.id)
                    val cs = db.comments(m.id)
                    comments += cs.size
                    val acts = db.activitiesFor(m.id)
                    activities += acts.size
                    add(
                        buildJsonObject {
                            put("tid", m.tid)
                            put("authorUin", m.authorUin)
                            put("content", m.content)
                            m.createTime?.let { put("createTime", it) }
                            put("location", m.location)
                            put("state", m.state.name)
                            put("completeness", m.completeness.name)
                            put("confidence", m.confidence.name)
                            put("archivedAt", m.createdAt)
                            putJsonArray("fields") {
                                db.momentFields(m.id).forEach { f ->
                                    add(buildJsonObject {
                                        put("field", f.field)
                                        put("source", f.sourceId)
                                        put("capturedAt", f.capturedAt)
                                    })
                                }
                            }
                            putJsonArray("media") {
                                media.forEach { mi ->
                                    add(buildJsonObject {
                                        put("url", mi.url)
                                        put("kind", mi.kind)
                                        put("state", mi.state)
                                        put("size", mi.size)
                                        put("hash", mi.hash)
                                        put("local", mi.localPath.substringAfterLast('/'))
                                    })
                                }
                            }
                            putJsonArray("comments") {
                                cs.forEach { c ->
                                    add(buildJsonObject {
                                        put("id", c.id)
                                        put("authorUin", c.authorUin)
                                        put("authorName", c.authorName)
                                        put("content", c.content)
                                        c.createTime?.let { put("createTime", it) }
                                        put("parentId", c.parentId)
                                    })
                                }
                            }
                            putJsonArray("activities") {
                                acts.forEach { a ->
                                    add(buildJsonObject {
                                        put("type", a.type)
                                        put("actor", a.actorName.ifBlank { a.actorUin })
                                        put("content", a.content)
                                        a.time?.let { put("time", it) }
                                    })
                                }
                            }
                        }
                    )
                }
            }
            entry("MEMORA/moments.json", momentsJson.toString())

            val friendsJson = buildJsonArray {
                for (f in friends) {
                    add(buildJsonObject {
                        put("uin", f.uin)
                        put("nickname", f.nickname)
                        put("remark", f.remark)
                        put("groups", f.groups)
                        put("goneState", f.goneState)
                        put("misses", f.misses)
                        put("firstSeenAt", f.firstSeenAt)
                        put("lastSeenAt", f.lastSeenAt)
                        put("tags", f.tags)
                        put("note", f.note)
                    })
                }
            }
            entry("MEMORA/friends.json", friendsJson.toString())

            entry(
                "MEMORA/albums.json",
                buildJsonArray {
                    albums.forEach { a ->
                        add(buildJsonObject {
                            put("name", a.name); put("count", a.count); put("desc", a.desc)
                            put("source", a.sourceId)
                            a.createTime?.let { put("createTime", it) }
                        })
                    }
                }.toString()
            )

            entry("MEMORA/account.json", buildJsonObject {
                put("uin", account?.uin ?: "")
                put("nickname", account?.nickname ?: "")
                put("exportedAt", now)
            }.toString())

            entry("MEMORA/html/index.html", OfflineHtml.build(context, db, accountId, moments, friends))
            entry("MEMORA/README.txt", readme(account?.uin ?: "", now))

            // 媒体副本
            for (m in moments) {
                for (mi in db.media(m.id)) {
                    if (mi.localPath.isBlank()) continue
                    val f = File(mi.localPath)
                    if (!f.exists() || f.length() <= 0) continue
                    val name = "MEMORA/media/${mi.id}-${f.name.substringAfter('-', f.name)}"
                    zip.putNextEntry(ZipEntry(name))
                    f.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                    hashes[name] = mi.hash.ifBlank { sha256(f) }
                    mediaFiles++
                    bytes += f.length()
                }
            }

            entry("MEMORA/manifest.json", buildJsonObject {
                put("format", "memora-archive")
                put("formatVersion", 1)
                put("exportedAt", now)
                put("uin", account?.uin ?: "")
                put("schemaVersion", 1)
                put("moments", moments.size)
                put("friends", friends.size)
                put("comments", comments)
                put("activities", activities)
                put("mediaFiles", mediaFiles)
                putJsonArray("files") {
                    hashes.forEach { (k, v) ->
                        add(buildJsonObject { put("path", k); put("sha256", v) })
                    }
                }
                put("containsCredentials", false)
                put("note", "本档案只包含可访问到的数据 缺失字段不代表内容在服务器上被删除")
            }.toString())
        }
        return ExportSummary(moments.size, friends.size, comments, activities, mediaFiles, bytes, "")
    }

    fun exportMomentsCsv(db: Db, accountId: String, out: OutputStream) {
        val moments = db.moments(accountId, limit = 100_000)
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("tid,createTime,state,completeness,confidence,comments,activities,content\n")
            for (m in moments) {
                val content = m.content.replace("\"", "\"\"").replace("\n", " ")
                w.write(
                    "${m.tid},${m.createTime ?: ""},${m.state.name},${m.completeness.name},${m.confidence.name}," +
                        "${m.commentCount},${m.activityCount},\"$content\"\n"
                )
            }
        }
    }

    fun suggestions(accountUin: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())
        return "MEMORA-$accountUin-$stamp.zip"
    }

    private fun readme(uin: String, now: Long): String = """
        MEMORA 本地档案包
        账号 $uin
        导出时间 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(now))}

        html/index.html 可以直接用浏览器打开 不需要联网
        moments.json 含每条内容的恢复状态与字段级来源
        friends.json 含好友首次发现与最后出现时间 以及消失判定
        manifest.json 记录文件清单与 SHA-256

        说明
        1 本包只包含本机确实采集到的数据
        2 缺少评论或图片 只表示没有找到证据 不代表服务器已经删除
        3 恢复状态区分当前可见 完整恢复 部分恢复 互动残留 本地副本
        4 不含任何登录凭证
    """.trimIndent()

    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

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

/** 导入：先生成预览，再写入；识别不了的结构直接拒绝，不猜。 */
object Importer {

    data class Preview(
        val valid: Boolean,
        val format: String,
        val moments: List<MomentRec>,
        val friends: List<FriendRec>,
        val warnings: List<String>,
        val message: String,
    )

    fun preview(context: Context, uri: Uri): Preview {
        val name = uri.lastPathSegment ?: ""
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        }.getOrDefault("")
        if (text.isBlank()) {
            return Preview(false, "", emptyList(), emptyList(), emptyList(), "文件为空或无法读取")
        }
        return parse(text, name)
    }

    fun parse(text: String, name: String): Preview {
        val trimmed = text.trim()
        val warnings = ArrayList<String>()
        return try {
            val root = Json.parseToJsonElement(trimmed)
            when (root) {
                is JsonObject -> fromObject(root, name, warnings)
                is JsonArray -> Preview(
                    true, "json-array",
                    parseMoments(root, warnings), emptyList(), warnings, "识别为说说数组"
                )
                else -> Preview(false, "", emptyList(), emptyList(), emptyList(), "无法识别的 JSON 结构")
            }
        } catch (t: Throwable) {
            Preview(false, "", emptyList(), emptyList(), emptyList(), "不是可解析的 JSON 或 ZIP 包")
        }
    }

    private fun fromObject(root: JsonObject, name: String, warnings: MutableList<String>): Preview {
        val format = runCatching { root["format"]?.jsonPrimitive?.contentOrNullSafe() }.getOrNull() ?: ""
        val momentsArr = (root["moments"] as? JsonArray)
        if (format == "memora-archive" || momentsArr != null) {
            val moments = momentsArr?.let { parseMoments(it, warnings) } ?: emptyList()
            val friendsArr = root["friends"] as? JsonArray
            val friends = friendsArr?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val uin = o.str("uin")
                if (uin.isBlank()) return@mapNotNull null
                FriendRec(uin, o.str("nickname"), o.str("remark"), "", o.str("groups"))
            } ?: emptyList()
            if (format.isNotBlank() && format != "memora-archive") warnings.add("格式标记为 $format")
            return Preview(true, format.ifBlank { "memora-json" }, moments, friends, warnings,
                "可导入 ${moments.size} 条内容 ${friends.size} 位好友")
        }
        if (root.containsKey("data") || root.containsKey("msglist")) {
            warnings.add("疑似其它工具导出的说说结构 只提取 tid 与正文")
            val arr = (root["msglist"] as? JsonArray) ?: (root["data"] as? JsonArray) ?: JsonArray(emptyList())
            return Preview(true, "third-party", parseMoments(arr, warnings), emptyList(), warnings,
                "可导入 ${arr.size} 条记录")
        }
        return Preview(false, "", emptyList(), emptyList(), warnings, "缺少 moments 字段")
    }

    private fun parseMoments(arr: JsonArray, warnings: MutableList<String>): List<MomentRec> {
        val out = ArrayList<MomentRec>()
        var skipped = 0
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val tid = o.str("tid").ifBlank { o.str("id") }
            if (tid.length < 5 || !tid.all { it.isLetterOrDigit() || it == '_' || it == '-' }) { skipped++; continue }
            val comments = (o["comments"] as? JsonArray)?.mapNotNull { c ->
                val co = c as? JsonObject ?: return@mapNotNull null
                CommentRec(
                    id = co.str("id").ifBlank { "$tid-${co.str("content").hashCode()}" },
                    authorUin = co.str("authorUin"), authorName = co.str("authorName"),
                    content = co.str("content"),
                    time = co["createTime"]?.jsonPrimitive?.content?.toLongOrNull(),
                )
            } ?: emptyList()
            val media = (o["media"] as? JsonArray)?.mapNotNull { mi ->
                (mi as? JsonObject)?.str("url")?.takeIf { it.startsWith("http") }
            } ?: emptyList()
            out.add(
                MomentRec(
                    tid = tid,
                    authorUin = o.str("authorUin"),
                    content = o.str("content"),
                    createTime = o["createTime"]?.jsonPrimitive?.content?.toLongOrNull(),
                    location = o.str("location"),
                    pics = media,
                    comments = comments,
                )
            )
        }
        if (skipped > 0) warnings.add("有 $skipped 条缺少有效内容 ID 已跳过")
        return out
    }

    fun apply(db: Db, accountId: String, preview: Preview): String {
        if (!preview.valid) return preview.message
        val now = System.currentTimeMillis()
        val taskId = "import-$now"
        db.createTask(
            com.memora.app.core.SyncTask(
                id = taskId, accountId = accountId, type = "import", label = "导入本地档案",
                status = com.memora.app.core.TaskStatus.RUNNING, createdAt = now, updatedAt = now,
                error = "", note = "",
            )
        )
        val page = com.memora.app.core.source.FetchedPage(
            status = com.memora.app.core.SourceStatus.AVAILABLE,
            moments = preview.moments, friends = preview.friends,
            rawCount = preview.moments.size + preview.friends.size,
        )
        val result = Ingest.commit(db, accountId, "local", page, now, taskId)
        runCatching { db.reindexAccount(accountId) }
        db.updateTask(taskId, com.memora.app.core.TaskStatus.COMPLETED, note = "新增 ${result.inserted} 重复 ${result.duplicates}")
        return "新增 ${result.inserted} 条 重复 ${result.duplicates} 条 好友 ${preview.friends.size} 位"
    }

    private fun JsonObject.str(key: String): String = runCatching {
        this[key]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
    }.getOrDefault("")

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { if (this is kotlinx.serialization.json.JsonNull) null else content }.getOrNull()
}
