package com.memora.app.core.net

import android.content.Context
import com.memora.app.core.SourceStatus
import com.memora.app.core.source.ActivityRec
import com.memora.app.core.source.AlbumRec
import com.memora.app.core.source.CommentRec
import com.memora.app.core.source.FriendRec
import com.memora.app.core.source.MomentRec
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

/** 一次扫描使用的会话上下文。 */
data class QzoneContext(
    val app: Context,
    val accountId: String,
    val uin: String,
    val cookie: String,
    val gtk: String,
    val qzonetoken: String = "",
)

data class ParseResult<T>(
    val status: SourceStatus,
    val items: List<T> = emptyList(),
    val detail: String = "",
    val hasMore: Boolean = false,
    val nextOffset: Int = 0,
    val rawCount: Int = 0,
    val unparsed: Int = 0,
)

/**
 * QQ 空间接口层。路径来自公开文档与开源项目的实测记录，
 * 但**尚未用真实账号验证**：任何一个返回结构变化都必须表现为 PARSE_ERROR，
 * 而不是被当成「没有内容」。
 */
object QzoneApi {

    private fun host(ctx: QzoneContext) = ctx.uin
    private fun gtk(ctx: QzoneContext) = if (ctx.gtk.isBlank()) "0" else ctx.gtk

    /** friend_mngr.cgi 已返回 500，改用实测存在（未登录回 -3000）的 friend_ship_manager.cgi。 */
    fun friendShipManager(ctx: QzoneContext): HttpResult {
        val url = "https://user.qzone.qq.com/proxy/domain/r.qzone.qq.com/cgi-bin/tfriend/friend_ship_manager.cgi" +
            "?uin=${host(ctx)}&do=1&rd=0.${System.currentTimeMillis() % 100000}&fupdate=1&clean=1" +
            "&g_tk=${gtk(ctx)}" + tokenParam(ctx) + "&format=json"
        return Net.get(url, ctx.cookie, referer(ctx))
    }

    private fun referer(ctx: QzoneContext) = "https://user.qzone.qq.com/${ctx.uin}"

    private fun tokenParam(ctx: QzoneContext): String =
        if (ctx.qzonetoken.isBlank()) "" else "&qzonetoken=" + QzoneAuth.encode(ctx.qzonetoken)

    fun msgList(ctx: QzoneContext, pos: Int, num: Int): HttpResult =
        Net.get(
            QzoneAuth.msgListUrl(host(ctx), gtk(ctx), ctx.qzonetoken, pos, num),
            ctx.cookie, referer(ctx),
        )

    fun feedStream(ctx: QzoneContext, resType: Int, pos: Int, page: Int): HttpResult {
        val url = "https://user.qzone.qq.com/proxy/domain/ic2.qzone.qq.com/cgi-bin/feeds/feeds3_html_more" +
            "?uin=${host(ctx)}&scope=0&view=1&flag=1&appid=2&pagenum=$page&pos=$pos&g_tk=${gtk(ctx)}" +
            tokenParam(ctx) + "&callback=_Callback&format=jsonp&res_type=$resType"
        return Net.get(url, ctx.cookie, referer(ctx))
    }

    fun feedsHtml(ctx: QzoneContext): HttpResult =
        Net.get(
            "https://user.qzone.qq.com/${host(ctx)}/311" +
                (if (ctx.qzonetoken.isBlank()) "" else "?qzonetoken=" + QzoneAuth.encode(ctx.qzonetoken)),
            ctx.cookie, referer(ctx),
        )

    fun albumList(ctx: QzoneContext): HttpResult {
        val t = System.currentTimeMillis() / 1000
        val url = "https://user.qzone.qq.com/proxy/domain/photo.qzone.qq.com/fcgi-bin/fcg_list_album_v3" +
            "?g_tk=${gtk(ctx)}&t=$t&hostUin=${host(ctx)}&uin=${host(ctx)}&appid=4" +
            "&inCharset=utf-8&outCharset=utf-8&source=qzone&plat=qzone&format=jsonp&notice=0&filter=1" +
            "&handset=4&pageNumModeSort=40&pageNumModeClass=15&needUserInfo=1&idcNum=4" +
            tokenParam(ctx) + "&callback=shine0_Callback"
        return Net.get(url, ctx.cookie, referer(ctx))
    }

    /* ---------------- 通用工具 ---------------- */

    /** 时间：秒 / 毫秒 / 字符串 / yyyy-MM-dd 都收，失败返回 null，绝不造 1970。 */
    fun time(value: Any?): Long? {
        when (value) {
            null -> return null
            is Number -> {
                val v = value.toLong()
                if (v <= 0) return null
                return if (v < 100_000_000_000L) v * 1000 else v
            }
            is String -> {
                val s = value.trim()
                if (s.isEmpty() || s == "0") return null
                s.toLongOrNull()?.let { return time(it) }
                val patterns = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd", "MM-dd HH:mm")
                for (p in patterns) {
                    runCatching {
                        val fmt = SimpleDateFormat(p, Locale.CHINA)
                        val d = fmt.parse(s) ?: return@runCatching
                        if (p.startsWith("MM")) {
                            val cal = java.util.Calendar.getInstance()
                            cal.time = d
                            cal.set(java.util.Calendar.YEAR, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
                            return cal.timeInMillis
                        }
                        return d.time
                    }
                }
            }
        }
        return null
    }

    fun clean(html: String): String {
        if (html.isBlank()) return ""
        val withEmoji = html.replace(Regex("\\[em\\](e?\\d+)\\[/em\\]")) { "" }
        return runCatching { Jsoup.parse(withEmoji.replace("<br/>", "\n").replace("<br>", "\n")).text() }
            .getOrElse { withEmoji.replace(Regex("<[^>]+>"), "") }
            .trim()
    }

    private fun str(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            if (o.has(k) && !o.isNull(k)) {
                when (val v = o.opt(k)) {
                    is String -> if (v.isNotBlank()) return v
                    is Number -> return v.toString()
                    is JSONObject -> {
                        // 例：lbs / poiname 这类嵌套对象，取可读名字，取不到就不猜
                        val inner = v.optString("name", "").ifBlank { v.optString("poiname", "") }
                        if (inner.isNotBlank()) return inner
                    }
                }
            }
        }
        return ""
    }

    private fun jsonArray(o: JSONObject, vararg keys: String): JSONArray? {
        for (k in keys) {
            val v = o.opt(k)
            if (v is JSONArray) return v
        }
        return null
    }

    /** -3000/-4001/-4002 是「需要登录」，必须和「没有内容」区分开。 */
    private fun isAuthError(root: JSONObject): Boolean {
        val code = root.optInt("code", 0)
        return code == -3000 || code == -4001 || code == -4002
    }

    private fun authMessage(root: JSONObject): String =
        root.optString("message").ifBlank { "需要登录 QQ 空间" }

    /* ---------------- 好友列表 ---------------- */

    fun parseFriends(body: String): ParseResult<FriendRec> {
        val root = Net.json(body)
            ?: return ParseResult(SourceStatus.PARSE_ERROR, detail = "响应不是 JSON" + snippet(body))
        if (isAuthError(root)) return ParseResult(SourceStatus.AUTH_REQUIRED, detail = authMessage(root))
        val data = root.optJSONObject("data") ?: root
        val items = jsonArray(data, "items_list", "friend_list", "friends")
            ?: return ParseResult(SourceStatus.PARSE_ERROR, detail = "未找到 items_list" + snippet(body))
        val groups = HashMap<String, String>()
        jsonArray(data, "gpname_list", "group_list")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                val id = str(g, "gpid", "groupid", "id")
                val name = str(g, "gpname", "groupname", "name")
                if (id.isNotEmpty()) groups[id] = name
            }
        }
        val out = ArrayList<FriendRec>(items.length())
        var unparsed = 0
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val uin = str(o, "fuin", "uin", "uin64").ifBlank { o.optLong("fuin", 0).takeIf { it > 0 }?.toString() ?: "" }
            if (uin.isBlank()) { unparsed++; continue }
            val gid = str(o, "groupid", "gpid", "gp")
            out.add(
                FriendRec(
                    uin = uin,
                    nickname = clean(str(o, "name", "nickname", "nick")),
                    remark = clean(str(o, "remark", "remarkname", "note")),
                    avatar = str(o, "img", "portrait", "avatar", "face"),
                    groups = groups[gid] ?: if (gid.isNotEmpty() && gid != "0") "#$gid" else "",
                    addedAt = time(o.opt("addtime") ?: o.opt("add_time") ?: o.opt("addFriendTime") ?: o.opt("addtime_str")),
                )
            )
        }
        val status = when {
            out.isEmpty() && unparsed > 0 -> SourceStatus.PARSE_ERROR
            out.isEmpty() -> SourceStatus.EMPTY
            else -> SourceStatus.AVAILABLE
        }
        return ParseResult(status, out, hasMore = false, nextOffset = 0, rawCount = items.length(), unparsed = unparsed)
    }

    /* ---------------- 当前说说（msglist_v6） ---------------- */

    fun parseMsgList(body: String, offset: Int, pageSize: Int): ParseResult<MomentRec> {
        val root = Net.json(body)
            ?: return ParseResult(SourceStatus.PARSE_ERROR, detail = "响应不是 JSON" + snippet(body))
        if (isAuthError(root)) return ParseResult(SourceStatus.AUTH_REQUIRED, detail = authMessage(root))
        val list = jsonArray(root, "msglist", "data")
            ?: return ParseResult(
                if (root.optInt("code", -1) == 0) SourceStatus.EMPTY else SourceStatus.SERVER_ERROR,
                detail = "code=${root.optString("code")} message=${root.optString("message")}" + snippet(body)
            )
        val out = ArrayList<MomentRec>(list.length())
        var unparsed = 0
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val tid = str(o, "tid", "id", "feedid")
            if (tid.isBlank()) { unparsed++; continue }
            val comments = ArrayList<CommentRec>()
            jsonArray(o, "comments", "commentlist", "cmtlist")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val c = arr.optJSONObject(j) ?: continue
                    comments.add(
                        CommentRec(
                            id = str(c, "commentid", "id", "cmtid").ifBlank { "$tid-$j" },
                            authorUin = str(c, "uin", "fuin"),
                            authorName = clean(str(c, "name", "nickname")),
                            content = clean(str(c, "content", "text")),
                            time = time(c.opt("create_time") ?: c.opt("time")),
                            parentId = str(c, "replyid", "parentid"),
                        )
                    )
                    jsonArray(c, "list_3", "replies", "replylist")?.let { reps ->
                        for (k in 0 until reps.length()) {
                            val r = reps.optJSONObject(k) ?: continue
                            comments.add(
                                CommentRec(
                                    id = str(r, "commentid", "id", "cmtid").ifBlank { "$tid-$j-r$k" },
                                    authorUin = str(r, "uin", "fuin"),
                                    authorName = clean(str(r, "name", "nickname")),
                                    content = clean(str(r, "content", "text")),
                                    time = time(r.opt("create_time") ?: r.opt("time")),
                                    parentId = str(c, "commentid", "id"),
                                )
                            )
                        }
                    }
                }
            }
            val pics = ArrayList<String>()
            jsonArray(o, "pic", "pics", "imagelist")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val p = arr.optJSONObject(j) ?: continue
                    listOf("url1", "url2", "url3", "url", "picUrl").forEach { k ->
                        val u = p.optString(k, "")
                        if (u.startsWith("http")) pics.add(u)
                    }
                }
            }
            val likes = ArrayList<Pair<String, String>>()
            jsonArray(o, "like", "likes", "like_list")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val l = arr.optJSONObject(j) ?: continue
                    likes.add(str(l, "uin", "fuin") to clean(str(l, "name", "nickname")))
                }
            }
            out.add(
                MomentRec(
                    tid = tid,
                    authorUin = str(o, "uin", "fuin").ifBlank { hostUinFallback(o) },
                    content = clean(str(o, "content", "text")),
                    createTime = time(o.opt("created_time") ?: o.opt("create_time") ?: o.opt("abstime")),
                    location = clean(str(o, "lbs", "location", "poiname")),
                    pics = pics.distinct(),
                    comments = comments,
                    likes = likes,
                    isPrivate = o.optInt("secret", 0) == 1 || o.optInt("privacy", 0) > 0,
                )
            )
        }
        val hasMore = root.optInt("hasmore", 0) == 1 || out.size >= pageSize
        return ParseResult(
            status = if (out.isEmpty() && unparsed > 0) SourceStatus.PARSE_ERROR else if (out.isEmpty()) SourceStatus.EMPTY else SourceStatus.AVAILABLE,
            items = out,
            hasMore = hasMore,
            nextOffset = offset + out.size,
            rawCount = list.length(),
            unparsed = unparsed,
        )
    }

    private fun hostUinFallback(o: JSONObject): String = str(o, "owner", "hostuin")

    /* ---------------- 历史互动流（get_feeds，按 res_type 分路） ---------------- */

    fun parseFeedStream(body: String, resType: Int, offset: Int, pageSize: Int): ParseResult<MomentRec> {
        val root = Net.json(body)
            ?: return ParseResult(SourceStatus.PARSE_ERROR, detail = "响应不是 JSON" + snippet(body))
        if (isAuthError(root)) return ParseResult(SourceStatus.AUTH_REQUIRED, detail = authMessage(root))
        val arr = jsonArray(root, "data", "feedlist", "items")
            ?: return ParseResult(SourceStatus.PARSE_ERROR, detail = "未找到 data 数组" + snippet(body))
        if (arr.length() == 0) {
            return ParseResult(SourceStatus.EMPTY, detail = "res_type=$resType 无返回", nextOffset = offset)
        }
        val out = ArrayList<MomentRec>()
        var unparsed = 0
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val blob = item.opt("data")?.toString().orEmpty() + " " + item.toString()
            val tid = Regex("(?:tid|feedid)[\"'=:\\s]{1,6}([A-Za-z0-9_-]{5,})").find(blob)?.groupValues?.getOrNull(1)
                ?: Regex("\"key\"\\s*:\\s*\"([^\"]+)\"").find(blob)?.groupValues?.getOrNull(1)?.filter { it.isDigit() }
                ?: ""
            if (tid.isBlank()) { unparsed++; continue }
            val content = Regex("(?:content|summary|title|text)[\"']?\\s*[:=]\\s*[\"']([^\"']{2,400})[\"']")
                .find(blob)?.groupValues?.getOrNull(1)?.let { clean(it) } ?: ""
            val time = time(item.opt("abstime") ?: item.opt("time") ?: item.opt("createtime"))
            val actor = Regex("(?:uin|fuin)[\"'=:\\s]{1,6}(\\d{5,12})").find(blob)?.groupValues?.getOrNull(1) ?: ""
            out.add(
                MomentRec(
                    tid = tid,
                    authorUin = actor,
                    content = content,
                    createTime = time,
                )
            )
        }
        val status = when {
            out.isEmpty() -> SourceStatus.PARSE_ERROR
            unparsed > out.size -> SourceStatus.PARSE_ERROR
            else -> SourceStatus.AVAILABLE
        }
        return ParseResult(
            status = status,
            items = out,
            detail = if (status == SourceStatus.PARSE_ERROR) "res_type=$resType 解析成功 $unparsed 条失败" + snippet(body) else "",
            hasMore = out.size >= pageSize,
            nextOffset = offset + arr.length(),
            rawCount = arr.length(),
            unparsed = unparsed,
        )
    }

    /* ---------------- feeds3 HTML（当前说说列表的主路径） ---------------- */

    fun parseFeedsHtml(body: String): ParseResult<MomentRec> {
        if (body.isBlank()) return ParseResult(SourceStatus.EMPTY, detail = "空页面")
        val doc = runCatching { Jsoup.parse(body) }.getOrNull()
            ?: return ParseResult(SourceStatus.PARSE_ERROR, detail = "HTML 无法解析")
        if (doc.title().contains("登录") || body.contains("login") && body.length < 20_000) {
            return ParseResult(SourceStatus.AUTH_REQUIRED, detail = "页面跳转到登录")
        }
        val nodes = doc.select("li.feed, div.feed, div.f-item, article.feed-item, [data-tid]")
        val out = ArrayList<MomentRec>()
        var unparsed = 0
        for (n in nodes) {
            val tid = (n.attr("data-tid").ifBlank { n.attr("data-key") })
                .let { if (it.all { c -> c.isDigit() }) it else Regex("(\\d{6,})").find(it)?.value ?: "" }
            if (tid.isBlank() || out.any { it.tid == tid }) { unparsed++; continue }
            val text = n.selectFirst(".f-info, .content, .feed-content, .txt, p")?.text()?.trim()
                ?: n.text().trim().take(400)
            val pics = n.select("img").mapNotNull { it.attr("src").takeIf { u -> u.startsWith("http") && !u.contains("qzone_icon") } }
                .distinct().take(9)
            val timeNode = n.selectFirst(".f-time, .time, abbr, .pubtime")
            out.add(
                MomentRec(
                    tid = tid,
                    authorUin = "",
                    content = clean(text),
                    createTime = time(timeNode?.attr("title")?.ifBlank { timeNode.text() }),
                    pics = pics,
                )
            )
        }
        return ParseResult(
            status = when {
                out.isNotEmpty() -> SourceStatus.AVAILABLE
                unparsed > 0 -> SourceStatus.PARSE_ERROR
                else -> SourceStatus.EMPTY
            },
            items = out,
            detail = if (out.isEmpty()) "未匹配到说说节点 页面 ${body.length} 字节" + snippet(body) else "",
            rawCount = nodes.size,
            unparsed = unparsed,
        )
    }

    /* ---------------- 相册 ---------------- */

    fun parseAlbums(body: String): ParseResult<AlbumRec> {
        val root = Net.json(body)
            ?: return ParseResult(SourceStatus.PARSE_ERROR, detail = "响应不是 JSON" + snippet(body))
        if (isAuthError(root)) return ParseResult(SourceStatus.AUTH_REQUIRED, detail = authMessage(root))
        val data = root.optJSONObject("data") ?: root
        val arr = jsonArray(data, "albumList", "albumlist", "list")
            ?: return ParseResult(SourceStatus.PARSE_ERROR, detail = "未找到 albumList" + snippet(body))
        val out = ArrayList<AlbumRec>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = str(o, "id", "albumid")
            if (id.isBlank()) continue
            val photos = ArrayList<Pair<String, String>>()
            jsonArray(o, "photos", "photolist")?.let { ps ->
                for (j in 0 until ps.length()) {
                    val p = ps.optJSONObject(j) ?: continue
                    val url = str(p, "url", "raw", "photourl")
                    if (url.startsWith("http")) photos.add(url to clean(str(p, "name", "desc")))
                }
            }
            out.add(
                AlbumRec(
                    albumId = id,
                    name = clean(str(o, "name", "title")),
                    count = o.optInt("total", o.optInt("count", photos.size)),
                    cover = str(o, "pre", "cover", "url"),
                    desc = clean(str(o, "desc", "description")),
                    createTime = time(o.opt("createtime") ?: o.opt("createTime")),
                    photos = photos,
                )
            )
        }
        return ParseResult(
            if (out.isEmpty()) SourceStatus.EMPTY else SourceStatus.AVAILABLE,
            out, rawCount = arr.length()
        )
    }

    /** 解析失败时给诊断留一段脱敏片段：不写 Cookie，只写结构。 */
    fun snippet(body: String): String {
        if (body.isBlank()) return ""
        val cleaned = body.replace(Regex("(skey|p_skey|p_uin|uin|g_tk)=([^&;\"\\s]+)")) { m ->
            "${m.groupValues[1]}=***"
        }
        return " · 样本: " + cleaned.take(160).replace("\n", " ")
    }
}
