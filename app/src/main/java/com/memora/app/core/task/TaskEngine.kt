package com.memora.app.core.task

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.memora.app.core.Checkpoint
import com.memora.app.core.Prefs
import com.memora.app.core.SessionStore
import com.memora.app.core.SourceState
import com.memora.app.core.SourceStatus
import com.memora.app.core.SyncTask
import com.memora.app.core.TaskStatus
import com.memora.app.core.db.Db
import com.memora.app.core.net.Net
import com.memora.app.core.net.QzoneContext
import com.memora.app.core.recovery.Ingest
import com.memora.app.core.source.ArchiveSource
import com.memora.app.core.source.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** 进程级前台标记：被「仅前台运行」策略使用。 */
object AppForeground {
    @Volatile var visible: Boolean = false
}

/**
 * 任务引擎。
 * - 每个 (来源, 数据流) 独立检查点，互不覆盖；
 * - 每页提交后立即落库，进程被杀最多丢正在解析的那一页；
 * - 暂停状态持久化到数据库，重启后可以继续；
 * - 失败按来源计，不因为一路失败结束整个任务。
 */
object TaskEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val paused = ConcurrentHashMap.newKeySet<String>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    val runningIds = MutableStateFlow<Set<String>>(emptySet())
    val progress = MutableStateFlow<Map<String, String>>(emptyMap())
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    fun isRunning(taskId: String): Boolean = jobs[taskId]?.isActive == true

    fun start(context: Context, taskId: String) {
        if (isRunning(taskId)) return
        val app = context.applicationContext
        val db = Db.get(app)
        val task = db.task(taskId) ?: return
        paused.remove(taskId)
        cancelled.remove(taskId)
        db.updateTask(taskId, TaskStatus.RUNNING, error = "")
        runningIds.value = runningIds.value + taskId
        jobs[taskId] = scope.launch {
            try {
                runTask(app, db, task)
            } catch (t: Throwable) {
                Log.e("MemoraEngine", "task $taskId crashed", t)
                db.updateTask(taskId, TaskStatus.FAILED, error = t.javaClass.simpleName + ": " + (t.message ?: ""))
                db.log("ERROR", "engine", "任务异常 ${t.javaClass.simpleName}")
            } finally {
                jobs.remove(taskId)
                runningIds.value = runningIds.value - taskId
                progress.value = progress.value - taskId
                changes.tryEmit(Unit)
                context.applicationContext.let { SyncService.stopIfIdle(it) }
            }
        }
        SyncService.ensure(app, taskId, task.label)
        changes.tryEmit(Unit)
    }

    fun pause(context: Context, taskId: String) {
        paused.add(taskId)
        Db.get(context).updateTask(taskId, TaskStatus.PAUSED, note = "用户暂停")
        jobs[taskId]?.cancel()
        changes.tryEmit(Unit)
    }

    fun cancel(context: Context, taskId: String) {
        cancelled.add(taskId)
        paused.remove(taskId)
        Db.get(context).updateTask(taskId, TaskStatus.CANCELLED)
        jobs[taskId]?.cancel()
        changes.tryEmit(Unit)
    }

    /** 应用启动时：把上次没跑完的任务标成暂停，交给用户或自动续跑。 */
    fun recoverInterrupted(context: Context) {
        val app = context.applicationContext
        val db = Db.get(app)
        val accountId = Prefs.activeAccount.value
        if (accountId.isBlank()) return
        for (t in db.resumeCandidates(accountId)) {
            if (t.status == TaskStatus.RUNNING || t.status == TaskStatus.PENDING) {
                db.updateTask(t.id, TaskStatus.PAUSED, note = "上次退出时未完成")
            }
        }
        if (Prefs.autoResume.value) {
            // 只续跑最近的一个，避免一次拉起多个前台服务
            db.resumeCandidates(accountId)
                .firstOrNull { it.status == TaskStatus.PAUSED || it.status == TaskStatus.RATE_LIMITED }
                ?.let { start(app, it.id) }
        }
    }

    private data class PlanStep(val sourceId: String, val stream: String, val pages: Int? = null)

    private fun planFor(type: String): List<PlanStep> = when (type) {
        "friend" -> listOf(PlanStep("friend", "main"))
        "current" -> listOf(PlanStep("current", "msglist"))
        "interaction" -> listOf(PlanStep("interaction", "res_type_1"), PlanStep("interaction", "res_type_2"))
        "legacy" -> listOf(PlanStep("legacy", "feeds_html"))
        "album" -> listOf(PlanStep("album", "albums"))
        // 归档新增：只碰最近的几页和未完成的媒体，不重复整库
        "incremental" -> buildList {
            if (Prefs.includeMoments.value) add(PlanStep("current", "msglist", pages = 2))
            if (Prefs.includeHistory.value) {
                add(PlanStep("interaction", "res_type_1", pages = 1))
                add(PlanStep("interaction", "res_type_2", pages = 1))
            }
            if (Prefs.includeFriends.value) add(PlanStep("friend", "main"))
        }
        // 查找更多历史：只走历史路径，不动当前列表
        "history" -> buildList {
            add(PlanStep("related", "pav_all", pages = 400))
            add(PlanStep("guestbook", "msgb", pages = 400))
            add(PlanStep("interaction", "res_type_1", pages = 400))
            add(PlanStep("interaction", "res_type_2", pages = 400))
            add(PlanStep("legacy", "feeds_html", pages = 400))
            if (Prefs.includeMoments.value) add(PlanStep("current", "msglist", pages = 400))
        }
        // 深度归档：把分页上限拉满，专门用来翻更早的年份
        "deep" -> buildList {
            if (Prefs.includeFriends.value) add(PlanStep("friend", "main"))
            add(PlanStep("related", "pav_all", pages = 400))
            add(PlanStep("guestbook", "msgb", pages = 400))
            if (Prefs.includeMoments.value) add(PlanStep("current", "msglist", pages = 400))
            if (Prefs.includeHistory.value) {
                add(PlanStep("interaction", "res_type_1", pages = 400))
                add(PlanStep("interaction", "res_type_2", pages = 400))
                add(PlanStep("legacy", "feeds_html", pages = 400))
            }
            if (Prefs.includeAlbums.value) add(PlanStep("album", "albums"))
        }
        "full" -> buildList {
            if (Prefs.includeFriends.value) add(PlanStep("friend", "main"))
            if (Prefs.includeMoments.value) add(PlanStep("current", "msglist"))
            if (Prefs.includeHistory.value) {
                add(PlanStep("interaction", "res_type_1"))
                add(PlanStep("interaction", "res_type_2"))
                add(PlanStep("legacy", "feeds_html"))
            }
            if (Prefs.includeAlbums.value) add(PlanStep("album", "albums"))
        }
        "quick" -> listOf(PlanStep("friend", "main"), PlanStep("current", "msglist", pages = 2))
        "media" -> emptyList()
        else -> listOf(PlanStep("current", "msglist"))
    }

    private suspend fun runTask(app: Context, db: Db, task: SyncTask) {
        if (task.type == "media") {
            val downloaded = MediaEngine.downloadPending(app, db, task.id)
            db.updateTask(
                task.id,
                if (downloaded.failed > 0 && downloaded.ok == 0) TaskStatus.PARTIAL else TaskStatus.COMPLETED,
                note = "成功 ${downloaded.ok} 失败 ${downloaded.failed}",
            )
            return
        }

        val cookie = SessionStore.cookie(app)
        if (cookie.isBlank()) {
            db.updateTask(task.id, TaskStatus.WAITING_AUTH, error = "尚未登录")
            return
        }
        if (Prefs.wifiOnly.value && !onWifi(app)) {
            db.updateTask(task.id, TaskStatus.PAUSED, note = "等待 Wi-Fi")
            return
        }
        if (Prefs.foregroundOnly.value && !AppForeground.visible) {
            db.updateTask(task.id, TaskStatus.PAUSED, note = "等待回到前台")
            return
        }

        val ctx = QzoneContext(
            app = app, accountId = task.accountId, uin = SessionStore.uin(app),
            cookie = cookie, gtk = Net.gtk(SessionStore.pSkey(app)),
            qzonetoken = SessionStore.token(app),
        )
        var anyFailure = false
        var anySuccess = false
        var authBlocked = false

        val plan = planFor(task.type)
        if (plan.isEmpty()) {
            db.updateTask(task.id, TaskStatus.COMPLETED)
            return
        }
        db.updateTask(task.id, note = "正在准备归档")

        for (step in plan) {
            if (cancelled.contains(task.id)) return
            while (paused.contains(task.id)) delay(400)
            val source = SourceRegistry.byId(step.sourceId) ?: continue
            val outcome = runStream(app, db, task, source, step.stream, ctx, step.pages)
            if (outcome == StreamOutcome.AUTH) { authBlocked = true; break }
            if (outcome == StreamOutcome.FAILED) anyFailure = true
            if (outcome == StreamOutcome.OK) anySuccess = true
        }

        // 内容与媒体分开：内容先落库，媒体最后补，媒体失败不影响内容归档结果
        if (!authBlocked && Prefs.downloadMedia.value) {
            db.updateTask(task.id, note = "正在保存图片和视频")
            MediaEngine.downloadPending(app, db, task.id)
        }

        val stat = db.stat(task.id)
        val status = when {
            authBlocked -> TaskStatus.WAITING_AUTH
            cancelled.contains(task.id) -> TaskStatus.CANCELLED
            paused.contains(task.id) -> TaskStatus.PAUSED
            anyFailure && anySuccess -> TaskStatus.PARTIAL
            anyFailure -> TaskStatus.FAILED
            else -> TaskStatus.COMPLETED
        }
        val media = db.mediaTally(task.accountId, task.createdAt)
        db.updateTask(
            task.id, status,
            note = "新增 ${stat.inserted} · 已存在 ${stat.duplicates} · 图片 ${media.imagesOk}/${media.imagesTotal} · 视频 ${media.videosOk}/${media.videosTotal}",
            error = if (authBlocked) "需要重新登录" else "",
        )
        if (status == TaskStatus.COMPLETED || status == TaskStatus.PARTIAL) {
            db.touchSync(task.accountId, System.currentTimeMillis())
            if (Prefs.notifyDone.value) SyncService.notifyDone(app, task.label, stat.inserted)
        }
        if (task.type == "friend" || task.type == "deep") {
            val seen = db.friends(task.accountId).filter { it.lastSeenAt >= task.createdAt }.map { it.uin }.toSet()
            if (seen.isNotEmpty()) {
                com.memora.app.core.friend.FriendDiff.apply(
                    db, task.accountId, seen, task.createdAt, Prefs.snapshotKeep.value,
                )
            }
        }
    }

    private enum class StreamOutcome { OK, FAILED, AUTH, EMPTY }

    private suspend fun runStream(
        app: Context, db: Db, task: SyncTask, source: ArchiveSource, stream: String, ctx: QzoneContext,
        pageCap: Int? = null,
    ): StreamOutcome {
        var cp: Checkpoint? = db.checkpoint(task.id, source.id, stream)
        var cursor = cp?.cursor?.ifBlank { null }
        var pages = cp?.pageCount ?: 0
        var failures = 0
        var sawData = false
        var idlePages = 0
        val pageSize = Prefs.pageSize.value.coerceIn(5, 50)
        // 任务显式给出的页数优先（深度归档 400 页），否则用设置里的上限
        val maxPages = (pageCap ?: Prefs.maxPages.value).coerceIn(1, 500)

        while (pages < maxPages) {
            if (cancelled.contains(task.id)) return StreamOutcome.FAILED
            while (paused.contains(task.id)) delay(300)
            if (Prefs.foregroundOnly.value && !AppForeground.visible) {
                db.updateTask(task.id, TaskStatus.PAUSED, note = "等待回到前台")
                paused.add(task.id)
                return StreamOutcome.FAILED
            }
            progress.value = progress.value + (task.id to "正在整理${source.name} · 第 ${pages + 1} 页")
            val page = try {
                source.fetch(ctx, stream, cursor, pageSize)
            } catch (t: Throwable) {
                SourcePageError(t.javaClass.simpleName + ": " + (t.message ?: ""))
            }
            val status = if (page is SourcePageError) SourceStatus.SERVER_ERROR else (page as com.memora.app.core.source.FetchedPage).status

            if (status == SourceStatus.AUTH_REQUIRED || status == SourceStatus.BLOCKED) {
                db.putSourceState(
                    SourceState(task.accountId, source.id, stream, status, summary(page), System.currentTimeMillis(), 0, db.stat(task.id).fetched)
                )
                return StreamOutcome.AUTH
            }
            if (status == SourceStatus.RATE_LIMITED) {
                failures++
                if (failures > 2) {
                    db.putSourceState(SourceState(task.accountId, source.id, stream, status, "连续限流", System.currentTimeMillis(), 0, 0))
                    return StreamOutcome.FAILED
                }
                delay(4_000L * failures)
                continue
            }
            if (page is SourcePageError || status == SourceStatus.SERVER_ERROR || status == SourceStatus.PARSE_ERROR || status == SourceStatus.NOT_FOUND) {
                failures++
                db.putSourceState(
                    SourceState(task.accountId, source.id, stream, status, summary(page), System.currentTimeMillis(), 0, 0)
                )
                db.log("WARN", "source", "${source.name}/$stream: ${summary(page).take(120)}")
                if (failures >= 2) return StreamOutcome.FAILED
                delay(1_500)
                continue
            }

            val fetched = page as com.memora.app.core.source.FetchedPage
            if (fetched.recordCount > 0) {
                val result = Ingest.commit(db, task.accountId, source.id, fetched, System.currentTimeMillis(), task.id)
                sawData = true
                idlePages = if (result.inserted > 0) 0 else idlePages + 1
                db.putSourceState(
                    SourceState(
                        task.accountId, source.id, stream,
                        if (fetched.status == SourceStatus.EMPTY) SourceStatus.EMPTY else SourceStatus.AVAILABLE,
                        "第 ${pages + 1} 页 ${fetched.recordCount} 条", System.currentTimeMillis(),
                        System.currentTimeMillis(), db.stat(task.id).fetched,
                    )
                )
                if (Prefs.downloadMedia.value) MediaEngine.queueCount(result.media)
            } else {
                idlePages++
                db.putSourceState(
                    SourceState(task.accountId, source.id, stream, fetched.status, fetched.detail, System.currentTimeMillis(), 0, 0)
                )
            }
            // 连续 4 页没有任何新内容，说明已经翻到底，继续请求只会浪费
            if (idlePages >= 4) {
                db.updateTask(task.id, note = "已翻到没有新内容")
                return StreamOutcome.OK
            }

            pages++
            cursor = fetched.nextCursor
            db.commitPage {
                checkpoint(
                    Checkpoint(
                        taskId = task.id, sourceId = source.id, stream = stream, cursor = cursor ?: "",
                        offset = 0, lastRecordId = fetched.moments.firstOrNull()?.tid ?: "",
                        lastTimestamp = fetched.moments.firstOrNull()?.createTime ?: 0,
                        pageCount = pages, recordCount = db.stat(task.id).fetched, failCount = failures,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            val startYear = Prefs.scanStartYear.value
            if (startYear > 0) {
                val oldest = fetched.moments.mapNotNull { it.createTime }.minOrNull()
                if (oldest != null && oldest < yearStart(startYear)) {
                    db.updateTask(task.id, note = "已到年份下限 $startYear")
                    return StreamOutcome.OK
                }
            }
            if (!fetched.hasMore || cursor == null || fetched.recordCount == 0) {
                return if (sawData) StreamOutcome.OK else StreamOutcome.EMPTY
            }
            delay(Prefs.requestGapMs.value.toLong())
        }
        return if (sawData) StreamOutcome.OK else StreamOutcome.EMPTY
    }

    private class SourcePageError(val reason: String)

    private fun summary(page: Any?): String = when (page) {
        is SourcePageError -> page.reason
        is com.memora.app.core.source.FetchedPage -> page.detail.ifBlank { page.status.detail }
        else -> ""
    }

    private fun yearStart(year: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.clear(); cal.set(year, java.util.Calendar.JANUARY, 1, 0, 0, 0)
        return cal.timeInMillis
    }

    private fun onWifi(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }.getOrDefault(false)
    }
}
