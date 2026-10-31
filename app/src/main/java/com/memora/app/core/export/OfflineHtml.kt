package com.memora.app.core.export

import android.content.Context
import com.memora.app.core.Friend
import com.memora.app.core.Moment
import com.memora.app.core.db.Db
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 离线档案浏览器：模板放在 assets，运行时填入 JSON。
 * 单文件、不引用任何 CDN、断网可搜索与筛选。
 */
object OfflineHtml {

    fun build(
        context: Context,
        db: Db,
        accountId: String,
        moments: List<Moment>,
        friends: List<Friend>,
    ): String {
        val template = runCatching {
            context.assets.open("offline.html").bufferedReader().use { it.readText() }
        }.getOrElse { "<html><body>offline template missing</body></html>" }

        val data = JSONArray()
        for (m in moments) {
            val o = JSONObject()
            o.put("tid", m.tid)
            o.put("time", m.createTime ?: 0L)
            o.put("state", m.state.name)
            o.put("stateLabel", m.state.label)
            o.put("completeness", m.completeness.label)
            o.put("confidence", m.confidence.label)
            o.put("content", m.content)
            o.put("location", m.location)
            o.put("comments", m.commentCount)
            o.put("activities", m.activityCount)
            val fields = JSONObject()
            db.momentFields(m.id).groupBy { it.field }.forEach { (field, rows) ->
                fields.put(field, rows.joinToString(", ") { it.sourceId })
            }
            o.put("fields", fields)
            val comments = JSONArray()
            db.comments(m.id).take(80).forEach { c ->
                comments.put(JSONObject().put("a", c.authorName.ifBlank { c.authorUin }).put("c", c.content))
            }
            o.put("commentList", comments)
            data.put(o)
        }

        val friendArr = JSONArray()
        for (f in friends) {
            friendArr.put(
                JSONObject()
                    .put("uin", f.uin)
                    .put("name", f.displayName)
                    .put("gone", f.goneState)
                    .put("first", f.firstSeenAt)
                    .put("last", f.lastSeenAt)
                    .put("groups", f.groups)
            )
        }

        val meta = JSONObject()
            .put("uin", db.account(accountId)?.uin ?: "")
            .put("exportedAt", System.currentTimeMillis())
            .put("format", "memora-archive")
            .put("formatVersion", 1)

        return template
            .replace("__DATA__", data.toString())
            .replace("__FRIENDS__", friendArr.toString())
            .replace("__META__", meta.toString())
    }

    fun stamp(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(t))
}
