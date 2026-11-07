package com.memora.app.core.recovery

import com.memora.app.core.Activity
import com.memora.app.core.Album
import com.memora.app.core.Comment
import com.memora.app.core.Completeness
import com.memora.app.core.Confidence
import com.memora.app.core.MediaItem
import com.memora.app.core.RecoveryState
import com.memora.app.core.db.Db
import com.memora.app.core.source.CommentRec
import com.memora.app.core.source.FetchedPage
import com.memora.app.core.source.MomentRec

data class IngestResult(
    val inserted: Int,
    val duplicates: Int,
    val comments: Int,
    val media: Int,
    val activities: Int,
    val affected: List<String>,
)

/**
 * 页面落库：一页一个事务。内容、来源字段、评论、媒体、互动一起提交，
 * 之后统一重算恢复状态（不在解析层下结论）。
 * 重复判定以内容 ID 为准：同一 tid 只补字段，不新增记录。
 */
object Ingest {

    fun mediaId(momentId: String, url: String, index: Int): String =
        "m$index-" + Integer.toHexString(url.hashCode()) + "-" + Integer.toHexString(momentId.hashCode())

    fun commit(
        db: Db,
        accountId: String,
        sourceId: String,
        page: FetchedPage,
        now: Long,
        taskId: String,
    ): IngestResult {
        var inserted = 0
        var duplicates = 0
        var commentCount = 0
        var mediaCount = 0
        var actCount = 0
        val affected = LinkedHashSet<String>()

        db.commitPage {
            page.friends.forEach { f ->
                db.upsertFriend(accountId, f.uin, f.nickname, f.remark, f.avatar, f.groups, now, f.addedAt)
            }

            page.albums.forEach { a ->
                db.upsertAlbum(
                    Album(
                        id = "$accountId:${a.albumId}", accountId = accountId, name = a.name,
                        count = a.count, coverUrl = a.cover, desc = a.desc, createTime = a.createTime,
                        sourceId = sourceId, capturedAt = now,
                    )
                )
                a.photos.forEachIndexed { i, p ->
                    val holder = Db.momentKey(accountId, "album-${a.albumId}")
                    upsertMedia(
                        accountId, "album-${a.albumId}",
                        MediaItem(
                            id = mediaId(holder, p.first, i), momentId = holder, url = p.first,
                            localPath = "", kind = 0, mime = "", size = 0, hash = "",
                            state = 0, bytes = 0, caption = p.second, albumId = a.albumId,
                        ),
                    )
                    mediaCount++
                }
            }

            page.moments.forEach { rec ->
                val existed = db.moment(accountId, rec.tid) != null
                upsertMoment(
                    accountId = accountId, tid = rec.tid, authorUin = rec.authorUin,
                    content = rec.content, createTime = rec.createTime, location = rec.location,
                    state = RecoveryState.UNKNOWN, completeness = Completeness.CLUE,
                    confidence = Confidence.LOW, sourceId = sourceId,
                    fields = fieldsOf(rec), now = now, lastSeenAt = now,
                )
                if (existed) duplicates++ else inserted++
                affected.add(rec.tid)

                rec.comments.forEach { c ->
                    upsertComment(accountId, rec.tid, commentOf(rec.tid, c, sourceId, now))
                    commentCount++
                }
                rec.likes.forEachIndexed { i, like ->
                    val id = if (like.first.isNotBlank()) "$accountId:${rec.tid}:like:${like.first}" else "$accountId:${rec.tid}:like:i$i"
                    upsertActivity(
                        accountId,
                        Activity(
                            id = id, accountId = accountId, actorUin = like.first, actorName = like.second,
                            type = "like", content = "", time = null, targetTid = rec.tid,
                            sourceId = sourceId, capturedAt = now, linkedMomentId = "",
                        ),
                    )
                    actCount++
                }
                rec.videos.forEachIndexed { i, url ->
                    val holder = Db.momentKey(accountId, rec.tid)
                    upsertMedia(
                        accountId, rec.tid,
                        MediaItem(
                            id = mediaId(holder, url, 100 + i), momentId = holder, url = url, localPath = "",
                            kind = 1, mime = "video/mp4", size = 0, hash = "", state = 0, bytes = 0,
                            caption = "", albumId = "",
                        ),
                    )
                    mediaCount++
                }
                rec.pics.forEachIndexed { i, url ->
                    val holder = Db.momentKey(accountId, rec.tid)
                    upsertMedia(
                        accountId, rec.tid,
                        MediaItem(
                            id = mediaId(holder, url, i), momentId = holder, url = url, localPath = "",
                            kind = if (url.contains(".mp4") || url.contains("video")) 1 else 0,
                            mime = "", size = 0, hash = "", state = 0, bytes = 0, caption = "", albumId = "",
                        ),
                    )
                    mediaCount++
                }
            }

            page.activities.forEach { a ->
                upsertActivity(
                    accountId,
                    Activity(
                        id = a.id, accountId = accountId, actorUin = a.actorUin, actorName = a.actorName,
                        type = a.type, content = a.content, time = a.time, targetTid = a.targetTid,
                        sourceId = sourceId, capturedAt = now, linkedMomentId = "",
                    ),
                )
                actCount++
                if (a.targetTid.isNotBlank()) affected.add(a.targetTid)
            }

            taskStat(taskId, page.recordCount, inserted, duplicates, page.unparsed)
        }

        // 互动指向本地还没有的内容：建「互动残留」占位记录，证据不丢
        val placeholders = page.activities.map { it.targetTid }
            .filter { it.isNotBlank() && db.moment(accountId, it) == null }
            .distinct()
        if (placeholders.isNotEmpty()) {
            db.commitPage {
                placeholders.forEach { tid ->
                    upsertMoment(
                        accountId = accountId, tid = tid, authorUin = "", content = "",
                        createTime = null, location = "", state = RecoveryState.INTERACTION_FRAGMENT,
                        completeness = Completeness.CLUE, confidence = Confidence.LOW,
                        sourceId = sourceId, fields = emptyList(), now = now,
                    )
                }
            }
            inserted += placeholders.size
            affected.addAll(placeholders)
        }

        RecoveryEngine.recompute(db, accountId, affected)
        // 搜索索引跟着内容走：正文与评论变化后重建这一条，失败不影响归档本身
        if (affected.isNotEmpty()) {
            affected.forEach { tid -> runCatching { db.reindexMoment(Db.momentKey(accountId, tid)) } }
        }
        return IngestResult(inserted, duplicates, commentCount, mediaCount, actCount, affected.toList())
    }

    fun fieldsOf(rec: MomentRec): List<String> {
        val f = ArrayList<String>(6)
        if (rec.content.isNotBlank()) f.add("body")
        if (rec.createTime != null) f.add("time")
        if (rec.pics.isNotEmpty()) f.add("media")
        if (rec.comments.isNotEmpty()) f.add("comments")
        if (rec.likes.isNotEmpty()) f.add("like")
        if (rec.authorUin.isNotBlank()) f.add("author")
        if (rec.location.isNotBlank()) f.add("location")
        return f
    }

    private fun commentOf(tid: String, c: CommentRec, sourceId: String, now: Long) = Comment(
        id = c.id.ifBlank { "$tid-${c.content.hashCode()}" },
        momentId = tid, authorUin = c.authorUin, authorName = c.authorName,
        content = c.content, createTime = c.time, parentId = c.parentId,
        sourceId = sourceId, capturedAt = now,
    )
}
