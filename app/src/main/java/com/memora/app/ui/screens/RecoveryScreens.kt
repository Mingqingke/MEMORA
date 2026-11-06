package com.memora.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memora.app.core.Checkpoint
import com.memora.app.core.Prefs
import com.memora.app.core.SourceStatus
import com.memora.app.core.SyncTask
import com.memora.app.core.TaskStat
import com.memora.app.core.TaskStatus
import com.memora.app.core.db.Db
import com.memora.app.core.source.SourceRegistry
import com.memora.app.core.task.Probe
import com.memora.app.core.task.TaskEngine
import com.memora.app.ui.AppState
import com.memora.app.ui.Route
import com.memora.app.ui.components.Badge
import com.memora.app.ui.components.BoxCard
import com.memora.app.ui.components.Chip
import com.memora.app.ui.components.Divider
import com.memora.app.ui.components.EmptyBlock
import com.memora.app.ui.components.EntryCard
import com.memora.app.ui.components.GhostButton
import com.memora.app.ui.components.ScreenTitle
import com.memora.app.ui.components.SectionLabel
import com.memora.app.ui.components.cardColor
import com.memora.app.ui.components.formatDay
import com.memora.app.ui.components.formatTime
import com.memora.app.ui.components.pressFeedback
import com.memora.app.ui.theme.LocalMemora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Scan

/** 运行摘要：一次归档的数据库快照，历史列表与详情共用同一口径。 */
data class RunSummary(
    val task: SyncTask,
    val stat: TaskStat,
    val media: Db.MediaTally,
) {
    val processed: Int get() = stat.inserted + stat.duplicates
    val contentRate: Float? get() = if (stat.fetched <= 0) null else processed.toFloat() / stat.fetched
}

@Composable
fun RecoveryScreen(onOpen: (Route) -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by AppState.account.collectAsState()
    val sources by AppState.sources.collectAsState()
    val tasks by AppState.tasks.collectAsState()
    val running by AppState.running.collectAsState()

    var probing by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf<List<com.memora.app.core.Moment>>(emptyList()) }
    var lastRun by remember { mutableStateOf<RunSummary?>(null) }
    var previousRun by remember { mutableStateOf<RunSummary?>(null) }

    LaunchedEffect(account?.id, tasks.firstOrNull()?.updatedAt) {
        val acc = account
        if (acc == null) { lastRun = null; previousRun = null; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            runCatching {
                val db = Db.get(context)
                val list = db.tasks(acc.id, limit = 2)
                lastRun = list.getOrNull(0)?.let { t -> RunSummary(t, db.stat(t.id), db.mediaTally(acc.id, t.createdAt, t.updatedAt + 1)) }
                previousRun = list.getOrNull(1)?.let { t -> RunSummary(t, db.stat(t.id), db.mediaTally(acc.id, t.createdAt, t.updatedAt + 1)) }
            }
        }
    }

    val active = tasks.firstOrNull { it.status.active }
    val usable = sources.count { it.status.usable }

    fun start(type: String, label: String) {
        if (account == null) { AppState.notify("先连接账号"); return }
        if (AppState.startTask(context, type, label) != null) onOpen(Route.Tasks)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(
            "归档", "$usable 个来源可用",
            actions = {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(c.accentSoft)
                        .clickable {
                            val acc = account ?: return@clickable
                            probing = true
                            scope.launch {
                                withContext(Dispatchers.IO) { Probe.all(context, acc.id) }
                                AppState.reload(context)
                                probing = false
                                AppState.notify("来源检查完成")
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) { Text(if (probing) "检查中" else "检查", fontSize = 12.sp, color = c.secondary) }
            },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 打开归档先看状态与进度，而不是先看一排入口
            item {
                val runningNow = active != null
                val fetched = lastRun?.stat?.fetched ?: 0
                val done = lastRun?.let { it.stat.inserted + it.stat.duplicates } ?: 0
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 22.dp,
                    colors = CardDefaults.defaultColors(color = cardColor()),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    runningNow -> "正在归档"
                                    lastRun == null -> "还没有归档过"
                                    else -> "上次归档"
                                },
                                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (lastRun != null) {
                                Text(
                                    formatDay(lastRun!!.task.updatedAt),
                                    fontSize = 11.sp, color = c.secondary,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "已保存 ${lastRun?.stat?.inserted ?: 0} · 已存在 ${lastRun?.stat?.duplicates ?: 0}",
                            fontSize = 12.sp, color = c.secondary,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            progress = when {
                                runningNow && fetched > 0 -> (done.toFloat() / fetched).coerceIn(0f, 1f)
                                runningNow -> null
                                lastRun != null -> 1f
                                else -> 0f
                            },
                        )
                        if (runningNow) {
                            val text = com.memora.app.core.task.TaskEngine.progress.collectAsState().value[active!!.id]
                            if (!text.isNullOrBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(text, fontSize = 10.5.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (runningNow) {
                                GhostButton(if (running.contains(active!!.id)) "暂停" else "继续") {
                                    if (running.contains(active!!.id)) TaskEngine.pause(context, active!!.id)
                                    else TaskEngine.start(context, active!!.id)
                                }
                                GhostButton("详情") { onOpen(Route.TaskDetail(active!!.id)) }
                            } else {
                                GhostButton("开始归档") { start("full", "完整归档") }
                                if (lastRun != null) {
                                    GhostButton("归档记录") { onOpen(Route.Tasks) }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("完整归档") { start("full", "完整归档") }
                    GhostButton("归档新增") { start("incremental", "归档新增内容") }
                    GhostButton("更多历史") { start("history", "查找更多历史内容") }
                    GhostButton("深度归档") { start("deep", "深度归档") }
                }
            }

            if (lastRun != null) {
                item {
                    SectionLabel("本次新增", if (added.isEmpty()) "这次没有新内容" else "归档完直接看")
                    BoxCard {
                        if (added.isEmpty()) {
                            Text(
                                if (running.isNotEmpty()) "还在归档，完成后这里会列出新内容" else "这次没有新增内容，可以试试深度归档",
                                fontSize = 12.sp, color = c.secondary,
                            )
                        } else {
                            added.forEachIndexed { i, m ->
                                if (i > 0) Divider()
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { onOpen(Route.MomentDetail(m.tid)) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            m.content.ifBlank { "（无正文）" },
                                            fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${m.state.label} · ${m.createTime?.let { formatDay(it) } ?: "时间未知"}",
                                            fontSize = 10.5.sp, color = c.secondary,
                                        )
                                    }
                                    Icon(MiuixIcons.ChevronForward, null, Modifier.size(13.dp), tint = c.secondary)
                                }
                            }
                        }
                    }
                }
                item {
                    SectionLabel("归档对比", "与上一次相比")
                    CompareCard(lastRun = lastRun, previous = previousRun)
                }
            }

            item {
                SectionLabel("最近归档", "${tasks.size} 次")
                BoxCard {
                    if (tasks.isEmpty()) {
                        Text("还没有归档记录", fontSize = 13.sp, color = c.secondary)
                    } else {
                        tasks.take(3).forEachIndexed { i, t ->
                            if (i > 0) Divider()
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpen(Route.TaskDetail(t.id)) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("${formatDay(t.updatedAt)} · ${t.label}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        t.note.ifBlank { t.status.label },
                                        fontSize = 10.5.sp, color = c.secondary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(t.status.label, fontSize = 10.5.sp, color = c.secondary)
                                Spacer(Modifier.width(4.dp))
                                Icon(MiuixIcons.ChevronForward, null, Modifier.size(13.dp), tint = c.secondary)
                            }
                        }
                        if (tasks.size > 3) {
                            Spacer(Modifier.height(8.dp))
                            GhostButton("全部记录") { onOpen(Route.Tasks) }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("查找来源", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(
                        if (showSources) "收起" else "$usable 个可用",
                        fontSize = 11.sp, color = c.secondary,
                        modifier = Modifier.clickable { showSources = !showSources }.padding(6.dp),
                    )
                }
                if (showSources) {
                    Spacer(Modifier.height(6.dp))
                    BoxCard {
                        SourceRegistry.all().forEachIndexed { i, src ->
                            if (i > 0) Divider()
                            val states = sources.filter { it.sourceId == src.id }
                            val status = when {
                                states.isEmpty() -> SourceStatus.UNKNOWN
                                states.any { it.status.needsUser } -> SourceStatus.AUTH_REQUIRED
                                states.any { !it.status.usable } -> states.first { !it.status.usable }.status
                                states.all { it.status == SourceStatus.EMPTY } -> SourceStatus.EMPTY
                                else -> SourceStatus.AVAILABLE
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(statusTone(status, c)))
                                Spacer(Modifier.width(9.dp))
                                Text(src.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text(
                                    if (status == SourceStatus.UNKNOWN) "未检测" else status.label,
                                    fontSize = 10.5.sp, color = c.secondary,
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(6.dp)) }
            item {
                GhostButton("导入旧档案") { onOpen(Route.ImportArchive) }
            }
        }
    }
}



@Composable
private fun CompareCard(lastRun: RunSummary?, previous: RunSummary?) {
    val c = LocalMemora.current
    BoxCard {
        if (lastRun == null) {
            Text("完成一次归档后这里会显示对比", fontSize = 12.sp, color = c.secondary)
            return@BoxCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(formatDay(lastRun.task.updatedAt), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    "新增 ${lastRun.stat.inserted} · 已存在 ${lastRun.stat.duplicates}",
                    fontSize = 11.sp, color = c.secondary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val diff = lastRun.stat.inserted - (previous?.stat?.inserted ?: 0)
                Text(
                    if (previous == null) "首次" else if (diff >= 0) "+$diff" else "$diff",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (previous == null) c.secondary else if (diff >= 0) c.ok else c.warm,
                )
                Text("与上次相比", fontSize = 10.sp, color = c.secondary)
            }
        }
        Spacer(Modifier.height(10.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        Row {
            CompareItem("图片", "${lastRun.media.imagesOk}/${lastRun.media.imagesTotal}", Modifier.weight(1f))
            CompareItem("视频", "${lastRun.media.videosOk}/${lastRun.media.videosTotal}", Modifier.weight(1f))
            CompareItem(
                "新增媒体",
                "${lastRun.media.ok - (previous?.media?.ok ?: 0)}",
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CompareItem(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(label, fontSize = 10.sp, color = LocalMemora.current.secondary)
    }
}


@Composable
fun TasksScreen(onOpen: (Route) -> Unit, onBack: () -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val account by AppState.account.collectAsState()
    val tasks by AppState.tasks.collectAsState()
    val running by AppState.running.collectAsState()
    var runs by remember { mutableStateOf<List<RunSummary>>(emptyList()) }

    LaunchedEffect(account?.id, tasks, running) {
        val acc = account ?: return@LaunchedEffect
        runs = withContext(Dispatchers.IO) {
            val db = Db.get(context)
            db.tasks(acc.id, limit = 60).map { t ->
                RunSummary(t, db.stat(t.id), db.mediaTally(acc.id, t.createdAt, t.updatedAt + 1))
            }
        }
        while (TaskEngine.runningIds.value.isNotEmpty()) {
            kotlinx.coroutines.delay(1500)
            runs = withContext(Dispatchers.IO) {
                val db = Db.get(context)
                db.tasks(acc.id, limit = 60).map { t ->
                    RunSummary(t, db.stat(t.id), db.mediaTally(acc.id, t.createdAt, t.updatedAt + 1))
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("归档记录", "${runs.size} 次", onBack = onBack)
        if (runs.isEmpty()) {
            EmptyBlock("还没有归档记录", "从归档页开始一次完整归档") {
                GhostButton("去归档") { onOpen(Route.Recovery) }
            }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(runs, key = { it.task.id }) { run ->
                val t = run.task
                BoxCard(onClick = { onOpen(Route.TaskDetail(t.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor(t.status, c)))
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${formatTime(t.createdAt)} · ${t.label}", fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "新增 ${run.stat.inserted} · 已存在 ${run.stat.duplicates} · 图片 ${run.media.imagesOk}/${run.media.imagesTotal}",
                                fontSize = 10.5.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(t.status.label, fontSize = 10.5.sp, color = c.secondary)
                    }
                }
            }
        }
    }
}

@Composable
fun TaskDetailScreen(taskId: String, onBack: () -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    var run by remember { mutableStateOf<RunSummary?>(null) }
    var cps by remember { mutableStateOf<List<Checkpoint>>(emptyList()) }
    var showTechnical by remember { mutableStateOf(false) }
    var showFormula by remember { mutableStateOf(false) }
    val running by AppState.running.collectAsState()

    LaunchedEffect(taskId, running) {
        suspend fun load() {
            withContext(Dispatchers.IO) {
                val db = Db.get(context)
                val t = db.task(taskId) ?: return@withContext
                run = RunSummary(t, db.stat(taskId), db.mediaTally(t.accountId, t.createdAt, Long.MAX_VALUE))
                cps = db.checkpoints(taskId)
            }
        }
        load()
        while (TaskEngine.runningIds.value.contains(taskId)) {
            kotlinx.coroutines.delay(1200)
            load()
        }
    }

    val r = run
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("归档详情", r?.task?.label, onBack = onBack)
        if (r == null) {
            EmptyBlock("读取中", "")
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                BoxCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                r.task.status == TaskStatus.RUNNING -> "正在归档"
                                r.task.status == TaskStatus.COMPLETED -> "归档完成"
                                r.task.status == TaskStatus.PARTIAL -> "部分完成"
                                r.task.status == TaskStatus.PAUSED -> "已暂停"
                                r.task.status == TaskStatus.WAITING_AUTH -> "需要重新登录"
                                r.task.status == TaskStatus.FAILED -> "未完成"
                                else -> r.task.status.label
                            },
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Badge("${r.stat.inserted} 新增", c.accent)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("已保存 ${r.stat.inserted} 条内容", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    val fraction = when {
                        r.task.status == TaskStatus.COMPLETED -> 1f
                        r.stat.fetched > 0 -> (r.processed.toFloat() / r.stat.fetched).coerceIn(0f, 1f)
                        else -> null
                    }
                    if (r.task.status == TaskStatus.RUNNING) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            progress = if (r.stat.fetched > 0) fraction else null,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), progress = fraction ?: 0f)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stageText(r), fontSize = 11.5.sp, color = c.secondary)
                    if (r.task.note.isNotBlank() && r.task.status == TaskStatus.RUNNING) {
                        Text(r.task.note, fontSize = 11.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            item {
                BoxCard {
                    Row {
                        Metric("已完成", "${r.processed}", c.ink, Modifier.weight(1f))
                        Metric("已跳过", "${r.stat.duplicates}", c.secondary, Modifier.weight(1f))
                        Metric("待处理", "${r.stat.failed + r.media.pending}", c.warm, Modifier.weight(1f))
                        Metric("用时", elapsed(r.task), c.ink, Modifier.weight(1f))
                    }
                }
            }

            item {
                BoxCard {
                    Text("图片与视频", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    MediaRow("图片", r.media.imagesOk, r.media.imagesTotal, c.accent)
                    Spacer(Modifier.height(8.dp))
                    MediaRow("视频", r.media.videosOk, r.media.videosTotal, c.warm)
                    if (r.media.failed > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text("${r.media.failed} 个文件暂时无法保存", fontSize = 11.sp, color = c.warm)
                    }
                }
            }

            item {
                BoxCard(onClick = { showFormula = !showFormula }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("归档质量", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(if (showFormula) "收起" else "怎么算的", fontSize = 11.sp, color = c.secondary)
                    }
                    Spacer(Modifier.height(10.dp))
                    QualityRow(
                        "内容处理", r.contentRate,
                        "${r.processed} / ${r.stat.fetched}",
                    )
                    Spacer(Modifier.height(8.dp))
                    QualityRow(
                        "图片保存",
                        if (r.media.imagesTotal == 0) null else r.media.imagesOk.toFloat() / r.media.imagesTotal,
                        "${r.media.imagesOk} / ${r.media.imagesTotal}",
                    )
                    Spacer(Modifier.height(8.dp))
                    QualityRow(
                        "视频保存",
                        if (r.media.videosTotal == 0) null else r.media.videosOk.toFloat() / r.media.videosTotal,
                        "${r.media.videosOk} / ${r.media.videosTotal}",
                    )
                    if (showFormula) {
                        Spacer(Modifier.height(10.dp))
                        Divider()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "内容处理 = （新增 + 已存在且通过校验）÷ 本次发现的去重内容。\n" +
                                "媒体保存 = 成功下载并通过文件校验的媒体 ÷ 实际进入下载的媒体。\n" +
                                "不提供「已删除内容恢复率」：服务器上还剩多少不可见内容没有可靠分母。",
                            fontSize = 11.sp, color = c.secondary, lineHeight = 18.sp,
                        )
                    }
                }
            }

            item {
                BoxCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("来源完成情况", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("${
                            cps.map { it.sourceId }.distinct().size
                        } / ${SourceRegistry.all().size}", fontSize = 11.sp, color = c.secondary)
                    }
                    if (cps.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("没有检查点", fontSize = 11.sp, color = c.secondary)
                    } else {
                        Spacer(Modifier.height(8.dp))
                        cps.forEach { cp ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(6.dp).clip(CircleShape)
                                        .background(if (cp.failCount == 0) c.ok else c.warm)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    SourceRegistry.byId(cp.sourceId)?.name ?: cp.sourceId,
                                    fontSize = 12.sp, modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (cp.failCount == 0) "${cp.pageCount} 页" else "${cp.pageCount} 页 · ${cp.failCount} 次失败",
                                    fontSize = 10.sp, color = c.secondary,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        r.task.status == TaskStatus.RUNNING -> GhostButton("暂停") { TaskEngine.pause(context, taskId) }
                        r.task.status == TaskStatus.PAUSED || r.task.status == TaskStatus.RATE_LIMITED ||
                            r.task.status == TaskStatus.FAILED -> GhostButton("继续") { TaskEngine.start(context, taskId) }
                        r.task.status.done -> GhostButton("再归档一次") { TaskEngine.start(context, taskId) }
                        else -> Unit
                    }
                    if (!r.task.status.done) GhostButton("取消") { TaskEngine.cancel(context, taskId) }
                }
            }

            item {
                Text(
                    if (showTechnical) "技术详情" else "技术详情 · 展开",
                    fontSize = 11.sp, color = c.secondary,
                    modifier = Modifier.clickable { showTechnical = !showTechnical }.padding(vertical = 6.dp),
                )
                if (showTechnical) {
                    BoxCard {
                        Text("任务 ${r.task.id}", fontSize = 10.5.sp, color = c.secondary)
                        Text("处理 ${r.stat.fetched} · 新增 ${r.stat.inserted} · 重复 ${r.stat.duplicates} · 失败 ${r.stat.failed}", fontSize = 10.5.sp, color = c.secondary)
                        if (r.task.error.isNotBlank()) {
                            Text(r.task.error, fontSize = 10.5.sp, color = c.danger)
                        }
                        Spacer(Modifier.height(6.dp))
                        cps.forEach { cp ->
                            Text(
                                "${cp.sourceId}/${cp.stream} · 游标 ${cp.cursor.ifBlank { "—" }} · ${cp.pageCount} 页",
                                fontSize = 10.sp, color = c.secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, tone: Color, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = tone, maxLines = 1)
        Text(label, fontSize = 10.sp, color = LocalMemora.current.secondary)
    }
}

@Composable
private fun MediaRow(label: String, ok: Int, total: Int, tone: Color) {
    val c = LocalMemora.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(38.dp))
        Box(Modifier.weight(1f)) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                progress = if (total == 0) 0f else ok.toFloat() / total,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("$ok / $total", fontSize = 11.sp, color = c.secondary)
    }
}

@Composable
private fun QualityRow(label: String, rate: Float?, detail: String) {
    val c = LocalMemora.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        Box(Modifier.weight(1f)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), progress = rate ?: 0f)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            rate?.let { "${(it * 100).toInt()}%" } ?: "—",
            fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
            color = if (rate == null) c.secondary else c.ink,
        )
    }
    Text(detail, fontSize = 9.5.sp, color = c.secondary, modifier = Modifier.padding(start = 64.dp, top = 2.dp))
}

private fun stageText(r: RunSummary): String = when {
    r.task.status == TaskStatus.COMPLETED -> "本次归档已完成"
    r.task.status == TaskStatus.WAITING_AUTH -> "登录已失效 需要重新登录"
    r.task.status == TaskStatus.PAUSED -> "已暂停 可以随时继续"
    r.task.status == TaskStatus.CANCELLED -> "已取消"
    r.task.status == TaskStatus.FAILED -> "有来源没有完成 可以继续重试"
    r.media.total > 0 && r.media.ok < r.media.total -> "正在保存图片和视频"
    r.task.status == TaskStatus.RUNNING && r.stat.fetched > 0 -> "正在整理空间内容"
    else -> "正在准备归档"
}

private fun elapsed(t: SyncTask): String {
    val end = if (t.status.done) t.updatedAt else System.currentTimeMillis()
    val sec = ((end - t.createdAt) / 1000).coerceAtLeast(0)
    return when {
        sec < 60 -> "${sec}秒"
        sec < 3600 -> "${sec / 60}分${sec % 60}秒"
        else -> "${sec / 3600}时${sec % 3600 / 60}分"
    }
}

@Composable
private fun statusColor(status: TaskStatus, c: com.memora.app.ui.theme.MemoraColors): Color = when (status) {
    TaskStatus.COMPLETED -> c.ok
    TaskStatus.FAILED, TaskStatus.CANCELLED -> c.danger
    TaskStatus.PAUSED, TaskStatus.WAITING_AUTH, TaskStatus.RATE_LIMITED, TaskStatus.PARTIAL -> c.warm
    TaskStatus.RUNNING -> c.accent
    else -> c.secondary
}
