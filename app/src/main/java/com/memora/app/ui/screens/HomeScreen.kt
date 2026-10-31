package com.memora.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memora.app.core.RecoveryState
import com.memora.app.core.SourceStatus
import com.memora.app.core.SyncTask
import com.memora.app.core.TaskStat
import com.memora.app.core.TaskStatus
import com.memora.app.core.db.Db
import com.memora.app.core.task.TaskEngine
import com.memora.app.ui.AppState
import com.memora.app.ui.Route
import com.memora.app.ui.components.Avatar
import com.memora.app.ui.components.BigRing
import com.memora.app.ui.components.EntryCard
import com.memora.app.ui.components.GhostButton
import com.memora.app.ui.components.MiniCard
import com.memora.app.ui.components.formatDay
import com.memora.app.ui.theme.LocalMemora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 首页：与 SCOPE 同版式。左卡始终是正方形，点击直接在卡内出进度。 */
@Composable
fun HomeScreen(onOpen: (Route) -> Unit, onSettings: () -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val account by AppState.account.collectAsState()
    val stats by AppState.stats.collectAsState()
    val tasks by AppState.tasks.collectAsState()
    val running by AppState.running.collectAsState()

    var expanded by remember { mutableStateOf(false) }
    var run by remember { mutableStateOf<RunSnapshot?>(null) }
    val active = tasks.firstOrNull { it.status.active }

    LaunchedEffect(account?.id, running, tasks.firstOrNull()?.updatedAt) {
        val acc = account ?: run { run = null; return@LaunchedEffect }
        suspend fun load() {
            withContext(Dispatchers.IO) {
                val db = Db.get(context)
                val t = active ?: runCatching { db.tasks(acc.id, limit = 1).firstOrNull() }.getOrNull()
                run = t?.let {
                    RunSnapshot(it, db.stat(it.id), db.mediaTally(it.accountId, it.createdAt))
                }
            }
        }
        load()
        while (active != null) {
            delay(1200)
            load()
        }
    }

    LaunchedEffect(active?.id) { if (active != null) expanded = true }

    // 固定横向比例：左大卡 + 右侧两小卡，宽屏只是把右列加宽，不再做成正方形
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val wide = screenW >= 840.dp
    val rightW = if (wide) 150.dp else 104.dp
    val heroH = if (wide) 250.dp else 216.dp

    val connected = account != null
    val fraction: Float? = when {
        run == null -> null
        run!!.task.status == TaskStatus.COMPLETED -> 1f
        run!!.stat.fetched > 0 -> (run!!.processed.toFloat() / run!!.stat.fetched).coerceIn(0f, 1f)
        else -> null
    }
    val ringTone = when {
        !connected -> c.danger.copy(alpha = 0.55f)
        active != null -> c.accent
        stats.moments > 0 -> c.ok
        else -> c.secondary.copy(alpha = 0.55f)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("首页", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                HeaderIcon(MiuixIcons.Search) { onOpen(Route.Search) }
                Spacer(Modifier.width(8.dp))
                HeaderIcon(MiuixIcons.Settings) { onSettings() }
            }

            Row(
                Modifier.fillMaxWidth().height(heroH),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    cornerRadius = 26.dp,
                    colors = CardDefaults.defaultColors(
                        color = if (connected) c.mint else c.dangerBg,
                        contentColor = if (connected) c.ink else c.dangerInk,
                    ),
                    pressFeedbackType = com.memora.app.ui.components.homePressFeedback(),
                    showIndication = true,
                    onClick = {
                        when {
                            !connected -> onOpen(Route.Login)
                            active != null -> expanded = !expanded
                            else -> {
                                expanded = true
                                if (AppState.startTask(context, "incremental", "归档新增内容") == null) {
                                    AppState.notify("先连接账号")
                                }
                            }
                        }
                    },
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(start = 18.dp, top = 18.dp, end = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when {
                                        !connected -> "未连接"
                                        active != null -> "归档中"
                                        stats.moments > 0 -> "已归档"
                                        else -> "未归档"
                                    },
                                    fontSize = 21.sp, fontWeight = FontWeight.Bold,
                                    lineHeight = 26.sp, modifier = Modifier.weight(1f),
                                    color = if (connected) c.ink else c.dangerInk,
                                )
                                if (connected) {
                                    Avatar(
                                        uin = account?.uin.orEmpty(),
                                        rawUrl = account?.avatar.orEmpty(),
                                        size = 34.dp,
                                        fallbackText = account?.nickname.orEmpty(),
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                when {
                                    !connected -> "点击连接 QQ"
                                    active != null -> stageOf(run, active)
                                    stats.moments > 0 -> "上次 ${formatDay(account?.lastSyncAt)}"
                                    else -> "点击开始归档"
                                },
                                fontSize = 12.5.sp,
                                color = if (connected) c.secondary else c.dangerInk.copy(alpha = 0.85f),
                                lineHeight = 18.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )

                            if (expanded && connected) {
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(0.78f),
                                    progress = if (active == null) (fraction ?: 1f) else fraction,
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    "已保存 ${run?.stat?.inserted ?: 0} · 已存在 ${run?.stat?.duplicates ?: 0}",
                                    fontSize = 10.sp, color = c.secondary, maxLines = 1,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (active != null) {
                                        PanelAction(if (running.contains(active.id)) "暂停" else "继续") {
                                            if (running.contains(active.id)) TaskEngine.pause(context, active.id)
                                            else TaskEngine.start(context, active.id)
                                        }
                                        PanelAction("取消") { TaskEngine.cancel(context, active.id) }
                                    } else {
                                        PanelAction("再归档") {
                                            AppState.startTask(context, "incremental", "归档新增内容")
                                        }
                                    }
                                }
                            }
                        }
                        BigRing(
                            fraction = when {
                                !connected -> null
                                active != null -> fraction
                                stats.moments > 0 -> 1f
                                else -> null
                            },
                            tone = ringTone,
                            showCheck = connected,
                            showPenguin = !connected,
                            modifier = Modifier.size(150.dp).align(Alignment.BottomEnd).offset(x = 34.dp, y = 38.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier.width(rightW).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MiniCard(
                        title = "说说",
                        value = "${stats.moments}",
                        sub = if (stats.moments > 0) "${stats.years.size} 年" else "未归档",
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(Route.Archive) },
                    )
                    MiniCard(
                        title = "好友",
                        value = "${stats.friends}",
                        sub = if (stats.goneFriends > 0) "${stats.goneFriends} 位消失" else "关系稳定",
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(Route.People) },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            EntryCard("我的档案", if (stats.moments > 0) "${stats.moments} 条内容 · 按年份浏览" else "归档后在这里阅读") {
                onOpen(Route.Archive)
            }
            Spacer(Modifier.height(10.dp))
            EntryCard("照片与视频", "本机文件 · 按时间浏览") { onOpen(Route.Media) }
            Spacer(Modifier.height(10.dp))
            EntryCard("查找历史内容", "更早的记录 · 互动残留") { onOpen(Route.Recovery) }
            Spacer(Modifier.height(10.dp))
            EntryCard("归档记录", "${tasks.size} 次归档 · 对比与重试") { onOpen(Route.Tasks) }
            if (!connected) {
                Spacer(Modifier.height(10.dp))
                EntryCard("导入旧档案", "以前导出的 JSON / ZIP") { onOpen(Route.ImportArchive) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 一次归档在首页用的快照。 */
data class RunSnapshot(
    val task: SyncTask,
    val stat: TaskStat,
    val media: Db.MediaTally,
) {
    val processed: Int get() = stat.inserted + stat.duplicates
}

fun stageOf(run: RunSnapshot?, active: SyncTask): String = when {
    run == null -> "正在准备归档"
    active.status == TaskStatus.WAITING_AUTH -> "需要重新登录"
    run.media.total > 0 && run.media.ok < run.media.total -> "正在保存图片和视频"
    run.stat.fetched > 0 -> "正在整理空间内容"
    else -> "正在准备归档"
}

@Composable
private fun PanelAction(text: String, onClick: () -> Unit) {
    val c = LocalMemora.current
    Box(
        Modifier.clip(RoundedCornerShape(9.dp))
            .background(c.ink.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) { Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = c.ink) }
}

@Composable
private fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val c = LocalMemora.current
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(c.card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(18.dp), tint = c.ink) }
}

@Composable
fun stateTone(state: RecoveryState, c: com.memora.app.ui.theme.MemoraColors): Color = when (state) {
    RecoveryState.CURRENT, RecoveryState.RECOVERED_FULL, RecoveryState.RECYCLE_BIN -> c.green
    RecoveryState.RECOVERED_PARTIAL, RecoveryState.INFERRED -> c.amber
    RecoveryState.INTERACTION_FRAGMENT -> c.danger
    else -> c.secondary
}

@Composable
fun statusTone(status: SourceStatus, c: com.memora.app.ui.theme.MemoraColors): Color = when (status) {
    SourceStatus.AVAILABLE -> c.green
    SourceStatus.EMPTY -> c.secondary
    SourceStatus.AUTH_REQUIRED, SourceStatus.BLOCKED -> c.amber
    SourceStatus.UNKNOWN -> c.secondary
    else -> c.danger
}
