package com.memora.app.core.source

import kotlinx.coroutines.async

import com.memora.app.core.SourceStatus
import com.memora.app.core.net.QzoneApi
import com.memora.app.core.net.QzoneContext
import com.memora.app.core.net.Net

data class StreamSpec(val id: String, val label: String, val note: String)

/**
 * 数据源适配器。每个来源自己决定游标语义，失败不拖垮其它来源。
 * 新增或替换接口只需要加一个实现，恢复引擎不需要改。
 */
interface ArchiveSource {
    val id: String
    val name: String
    val purpose: String
    val streams: List<StreamSpec>
    val verified: Boolean get() = false
    val verifiedNote: String get() = "结构来自公开文档与开源项目实测记录 尚未用本机账号验证"

    suspend fun fetch(ctx: QzoneContext, stream: String, cursor: String?, pageSize: Int): FetchedPage
}

/** 当前可见说说：以 detail 接口为准，正文、图片、评论最完整。 */
object CurrentFeedsSource : ArchiveSource {
    override val id = "current"
    override val name = "当前说说"
    override val purpose = "读取当前可访问的说说正文、图片与评论，作为内容基准"
    override val streams = listOf(StreamSpec("msglist", "说说列表", "按发布时间倒序 每页一次请求"))
    override val verified = true

    override suspend fun fetch(ctx: QzoneContext, stream: String, cursor: String?, pageSize: Int): FetchedPage {
        val offset = cursor?.toIntOrNull() ?: 0
        val res = QzoneApi.msgList(ctx, offset, pageSize)
        if (!res.ok) {
            return FetchedPage(statusFor(res), res.error.ifBlank { "HTTP ${res.code}" }, nextCursor = cursor, hasMore = false)
        }
        val parsed = QzoneApi.parseMsgList(res.body, offset, pageSize)
        return FetchedPage(
            status = parsed.status,
            detail = parsed.detail,
            moments = parsed.items,
            nextCursor = if (parsed.hasMore) parsed.nextOffset.toString() else null,
            hasMore = parsed.hasMore,
            rawCount = parsed.rawCount,
            unparsed = parsed.unparsed,
        )
    }
}

/**
 * 历史互动流。res_type 之间不是包含关系：实测存在 201 / 83 条两种结果，
 * 因此每一路独立游标、独立状态、独立解析，最后按内容 ID 合并。
 */
/**
 * 自研聚合来源：一次取三条互不相同的链路，本地按 tid 合并去重。
 * 单条链路失效不影响整体，比照搬外部项目的单接口更耐用。
 */
object DiscoverSource : ArchiveSource {
    override val id = "discover"
    override val name = "综合发现"
    override val purpose = "同时走与我相关 留言板 互动流 合并去重 任何一条可用就有结果"
    override val streams = listOf(StreamSpec("merge", "综合流", "多来源并发 本地合并"))
    override val verified = false

    override suspend fun fetch(ctx: QzoneContext, stream: String, cursor: String?, pageSize: Int): FetchedPage {
        val page = cursor?.toIntOrNull() ?: 0
        val offset = page * 10
        val merged = LinkedHashMap<String, MomentRec>()
        var okCount = 0
        var lastStatus = SourceStatus.EMPTY
        kotlinx.coroutines.coroutineScope {
            val pav = async { runCatching { QzoneApi.pavAll(ctx, offset, 10) }.getOrNull() }
            val gb = async { runCatching { QzoneApi.guestbook(ctx, offset, 10) }.getOrNull() }
            val feed = async { runCatching { QzoneApi.feedStream(ctx, 1, offset, page + 1) }.getOrNull() }
            val bodies = listOf(
                pav.await()?.takeIf { it.ok }?.body?.let { QzoneApi.parsePavAll(it, offset, pageSize) },
                gb.await()?.takeIf { it.ok }?.body?.let { QzoneApi.parseGuestbook(it, offset, pageSize) },
                feed.await()?.takeIf { it.ok }?.body?.let { QzoneApi.parseFeedStream(it, 1, offset, pageSize) },
            )
            bodies.filterNotNull().forEach { parsed ->
                if (parsed.items.isNotEmpty()) okCount++
                lastStatus = parsed.status
                parsed.items.forEach { rec -> merged.putIfAbsent(rec.tid, rec) }
            }
        }
        val items = merged.values.toList()
        return FetchedPage(
            status = when {
                items.isNotEmpty() -> SourceStatus.AVAILABLE
                okCount > 0 -> SourceStatus.EMPTY
                else -> lastStatus
            },
            detail = if (items.isEmpty()) "三条链路都没有数据" else "${okCount} 条链路合并 ${items.size} 条",
            moments = items,
            nextCursor = if (items.isNotEmpty()) (page + 1).toString() else null,
            hasMore = items.isNotEmpty(),
            rawCount = items.size,
        )
    }
}

/** 「与我相关」：说说删了，别人的评论/点赞记录还留着，是找回已删除说说的主来源。 */
object RelatedFeedSource : ArchiveSource {
    override val id = "related"
    override val name = "与我相关"
    override val purpose = "别人对我的评论与点赞记录，说说被删后仍可能保留"
    override val streams = listOf(StreamSpec("pav_all", "与我相关流", "offset 每页 +10 独立分页"))
    override val verified = false

    override suspend fun fetch(ctx: QzoneContext, stream: String, cursor: String?, pageSize: Int): FetchedPage {
        val offset = cursor?.toIntOrNull() ?: 0
        val num = pageSize.coerceIn(10, 30)
        val res = QzoneApi.pavAll(ctx, offset, num)
        if (!res.ok) {
            return FetchedPage(statusFor(res), res.error.ifBlank { "HTTP ${res.code}" }, nextCursor = cursor, hasMore = false)
        }
        val parsed = QzoneApi.parsePavAll(res.body, offset, pageSize)
        return FetchedPage(
            status = parsed.status,
            detail = parsed.detail,
            moments = parsed.items,
            nextCursor = if (parsed.hasMore) parsed.nextOffset.toString() else null,
            hasMore = parsed.hasMore,
            rawCount = parsed.rawCount,
            unparsed = parsed.unparsed,
        )
    }
}

object GuestbookSource : ArchiveSource {
    override val id = "guestbook"
    override val name = "留言板"
    override val purpose = "自己空间与好友的留言，常比说说更早，且不随说说删除"
    override val streams = listOf(StreamSpec("msgb", "留言列表", "按时间倒序 每页一次请求"))
    override val verified = false

    override suspend fun fetch(ctx: QzoneContext, stream: String, cursor: String?, pageSize: Int): FetchedPage {
        val start = cursor?.toIntOrNull() ?: 0
        val res = QzoneApi.guestbook(ctx, start, pageSize)
        if (!res.ok) {
            return FetchedPage(statusFor(res), res.error.ifBlank { "HTTP ${res.code}" }, nextCursor = cursor, hasMore = false)
        }
        val parsed = QzoneApi.parseGuestbook(res.body, start, pageSize)
        return FetchedPage(
            status = parsed.status,
            detail = parsed.detail,
            moments = parsed.items,
            nextCursor = if (parsed.hasMore) parsed.nextOffset.toString() else null,
            hasMore = parsed.hasMore,
            rawCount = parsed.rawCount,
            unparsed = parsed.unparsed,
        )
    }
}

object InteractionFeedSource : ArchiveSource {
    override val id = "interaction"
    override val name = "历史互动流"
    override val purpose = "从互动 feed 里找回已经从说说列表消失的内容与互动痕迹"
    override val streams = listOf(
        StreamSpec("res_type_1", "互动流 1", "独立分页 独立状态"),
        StreamSpec("res_type_2", "互动流 2", "与互动流 1 内容可能不同"),
    )

    override suspend fun fetch(ctx: QzoneContext, stream: String, cursor: String?, pageSize: Int): FetchedPage {
        val resType = if (stream.endsWith("2")) 2 else 1
        val (pos, page) = parseCursor(cursor)
        val res = QzoneApi.feedStream(ctx, resType, pos, page)
        if (!res.ok) {
            return FetchedPage(statusFor(res), res.error.ifBlank { "HTTP ${res.code}" }, nextCursor = cursor)
        }
        val parsed = QzoneApi.parseFeedStream(res.body, resType, pos, pageSize)
        return FetchedPage(
            status = parsed.status,
            detail = parsed.detail,
            moments = parsed.items,
            nextCursor = if (parsed.hasMore) "${parsed.nextOffset},${page + 1}" else null,
            hasMore = parsed.hasMore,
            rawCount = parsed.rawCount,
            unparsed = parsed.unparsed,
        )
    }

    private fun parseCursor(cursor: String?): Pair<Int, Int> {
        if (cursor.isNullOrBlank()) return 0 to 1
        val parts = cursor.split(",")
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 1)
    }
}

/** 旧版历史 Feed：HTML 结构，接口失效时不能拖死其它来源。 */
object LegacyFeedsSource : ArchiveSource {
    override val id = "legacy"
    override val name = "旧版历史 Feed"
    override val purpose = "解析历史页面里残留的说说卡片，补齐更早的内容"
    override val streams = listOf(StreamSpec("feeds_html", "历史页面", "单页抓取 无可靠分页"))

    override suspend fun fetch(ctx: QzoneContext, stream: String, cursor: String?, pageSize: Int): FetchedPage {
        val res = QzoneApi.feedsHtml(ctx)
        if (!res.ok) return FetchedPage(statusFor(res), res.error.ifBlank { "HTTP ${res.code}" })
        val parsed = QzoneApi.parseFeedsHtml(res.body)
        return FetchedPage(
            status = parsed.status, detail = parsed.detail, moments = parsed.items,
            nextCursor = null, hasMore = false, rawCount = parsed.rawCount, unparsed = parsed.unparsed,
        )
    }
}

/** 相册：媒体副本是恢复整条内容的关键证据。 */
object AlbumSource : ArchiveSource {
    override val id = "album"
    override val name = "相册与视频"
    override val purpose = "归档相册结构、封面与照片地址，图片本身会被下载到本地"
    override val streams = listOf(StreamSpec("albums", "相册列表", "单次请求返回全部相册"))

    override suspend fun fetch(ctx: QzoneContext, stream: String, cursor: String?, pageSize: Int): FetchedPage {
        val res = QzoneApi.albumList(ctx)
        if (!res.ok) return FetchedPage(statusFor(res), res.error.ifBlank { "HTTP ${res.code}" })
        val parsed = QzoneApi.parseAlbums(res.body)
        return FetchedPage(
            status = parsed.status, detail = parsed.detail, albums = parsed.items,
            rawCount = parsed.rawCount, hasMore = false,
        )
    }
}

/** 好友列表：一次拿全，变化交给快照差异判断，不在这一层下结论。 */
object FriendSource : ArchiveSource {
    override val id = "friend"
    override val name = "好友列表"
    override val purpose = "读取好友昵称 备注 分组 头像 生成关系快照"
    override val streams = listOf(StreamSpec("main", "好友列表", "单次请求"))

    override suspend fun fetch(ctx: QzoneContext, stream: String, cursor: String?, pageSize: Int): FetchedPage {
        val res = QzoneApi.friendShipManager(ctx)
        if (!res.ok) return FetchedPage(statusFor(res), res.error.ifBlank { "HTTP ${res.code}" })
        val parsed = QzoneApi.parseFriends(res.body)
        return FetchedPage(
            status = parsed.status, detail = parsed.detail, friends = parsed.items,
            rawCount = parsed.rawCount, unparsed = parsed.unparsed,
        )
    }
}

/**
 * 官方回收站：目前只有相册回收站有公开实现记录，
 * 说说 / 日志 / 留言板是否存在同类型入口必须先由用户在官方页面确认。
 * 这里不伪造接口，只暴露「需要用户确认」的真实状态。
 */
object RecycleBinSource : ArchiveSource {
    override val id = "recycle"
    override val name = "官方回收站"
    override val purpose = "只在官方回收站确实存在时使用 由用户在官方页面操作后回填"
    override val streams = listOf(StreamSpec("manual", "人工确认", "不自动请求"))
    override val verified = false
    override val verifiedNote = "仅有相册回收站的可查记录 其它类型未验证"

    override suspend fun fetch(ctx: QzoneContext, stream: String, cursor: String?, pageSize: Int): FetchedPage =
        FetchedPage(
            status = SourceStatus.UNSUPPORTED,
            detail = "回收站内容只能由用户在官方页面确认 本应用不伪造该入口",
        )
}

/** 本地旧档案导入：不是网络来源，但同样进入证据链。 */
object LocalArchiveSource : ArchiveSource {
    override val id = "local"
    override val name = "本地旧档案"
    override val purpose = "导入用户以前导出的 JSON / HTML / ZIP / 数据库"
    override val streams = listOf(StreamSpec("import", "文件导入", "由用户选择文件"))
    override val verified = true

    override suspend fun fetch(ctx: QzoneContext, stream: String, cursor: String?, pageSize: Int): FetchedPage =
        FetchedPage(status = SourceStatus.UNSUPPORTED, detail = "请从恢复中心的导入入口选择文件")
}

object SourceRegistry {
    private val all: List<ArchiveSource> = listOf(
        CurrentFeedsSource, InteractionFeedSource, LegacyFeedsSource, AlbumSource,
        FriendSource, RecycleBinSource, LocalArchiveSource,
    )

    fun all(): List<ArchiveSource> = all
    fun byId(id: String): ArchiveSource? = all.firstOrNull { it.id == id }
    fun names(): Map<String, String> = all.associate { it.id to it.name }

    fun statusFor(res: com.memora.app.core.net.HttpResult): SourceStatus = when {
        res.error.isNotBlank() -> SourceStatus.SERVER_ERROR
        res.code == 401 || res.code == 403 -> SourceStatus.AUTH_REQUIRED
        res.code == 404 -> SourceStatus.NOT_FOUND
        res.code == 429 -> SourceStatus.RATE_LIMITED
        res.code >= 500 -> SourceStatus.SERVER_ERROR
        res.code in 300..399 -> SourceStatus.AUTH_REQUIRED
        res.code !in 200..299 -> SourceStatus.SERVER_ERROR
        res.body.isBlank() -> SourceStatus.EMPTY
        isLoginPage(res.body) -> SourceStatus.AUTH_REQUIRED
        else -> SourceStatus.AVAILABLE
    }

    private fun isLoginPage(body: String): Boolean {
        if (body.length > 60_000) return false
        return body.contains("login.qq.com") || body.contains("ptlogin") ||
            (body.contains("登录") && body.contains("QQ") && body.length < 8_000)
    }
}

internal fun statusFor(res: com.memora.app.core.net.HttpResult): SourceStatus = SourceRegistry.statusFor(res)
