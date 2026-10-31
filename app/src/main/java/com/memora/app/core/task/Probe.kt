package com.memora.app.core.task

import android.content.Context
import com.memora.app.core.Prefs
import com.memora.app.core.SessionStore
import com.memora.app.core.SourceState
import com.memora.app.core.SourceStatus
import com.memora.app.core.db.Db
import com.memora.app.core.net.Net
import com.memora.app.core.net.QzoneContext
import com.memora.app.core.source.ArchiveSource
import com.memora.app.core.source.SourceRegistry

/**
 * 来源探测：对每个来源发一次真实请求，把结果写进 source_states。
 * 探测不写入档案数据，只回答「这条路径现在还能不能用」。
 */
object Probe {

    data class Row(
        val sourceId: String,
        val stream: String,
        val status: SourceStatus,
        val detail: String,
        val records: Int,
        val httpCode: Int,
        val latencyMs: Long,
    )

    suspend fun one(context: Context, accountId: String, source: ArchiveSource): List<Row> {
        val app = context.applicationContext
        val db = Db.get(app)
        val cookie = SessionStore.cookie(app)
        if (cookie.isBlank()) {
            return source.streams.map {
                Row(source.id, it.id, SourceStatus.AUTH_REQUIRED, "尚未登录", 0, 0, 0)
            }
        }
        val ctx = QzoneContext(
            app = app, accountId = accountId, uin = SessionStore.uin(app),
            cookie = cookie, gtk = Net.gtk(SessionStore.pSkey(app)),
            qzonetoken = SessionStore.token(app),
        )
        val out = ArrayList<Row>()
        for (stream in source.streams) {
            val started = System.currentTimeMillis()
            val page = runCatching { source.fetch(ctx, stream.id, null, Prefs.pageSize.value.coerceAtMost(10)) }
                .getOrElse {
                    out.add(
                        Row(source.id, stream.id, SourceStatus.SERVER_ERROR, it.javaClass.simpleName, 0, 0,
                            System.currentTimeMillis() - started)
                    )
                    continue
                }
            val row = Row(
                sourceId = source.id, stream = stream.id, status = page.status,
                detail = page.detail.ifBlank { page.status.detail },
                records = page.recordCount, httpCode = 0,
                latencyMs = System.currentTimeMillis() - started,
            )
            out.add(row)
            val now = System.currentTimeMillis()
            val existing = db.sourceStates(accountId).firstOrNull { it.sourceId == source.id && it.stream == stream.id }
            db.putSourceState(
                SourceState(
                    accountId = accountId, sourceId = source.id, stream = stream.id,
                    status = row.status, detail = row.detail,
                    lastProbeAt = now,
                    lastOkAt = if (row.status.usable) now else (existing?.lastOkAt ?: 0),
                    records = row.records,
                )
            )
        }
        return out
    }

    suspend fun all(context: Context, accountId: String): List<Row> =
        SourceRegistry.all().flatMap { one(context, accountId, it) }
}
