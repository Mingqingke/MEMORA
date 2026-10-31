package com.memora.app.ui.screens

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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memora.app.core.RecoveryState
import com.memora.app.core.db.Db
import com.memora.app.ui.AppState
import com.memora.app.ui.Route
import com.memora.app.ui.components.BoxCard
import com.memora.app.ui.components.Divider
import com.memora.app.ui.components.GhostButton
import com.memora.app.ui.components.ScreenTitle
import com.memora.app.ui.components.SectionLabel
import com.memora.app.ui.components.formatBytes
import com.memora.app.ui.theme.LocalMemora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import java.io.File

private data class CheckState(
    val moments: Int = 0,
    val comments: Int = 0,
    val mediaTotal: Int = 0,
    val mediaOk: Int = 0,
    val mediaFailed: Int = 0,
    val missingFiles: Int = 0,
    val emptyHash: Int = 0,
    val indexed: Int = 0,
    val unfinished: Int = 0,
    val partial: Int = 0,
    val fragments: Int = 0,
    val bytes: Long = 0,
    val scanned: Int = 0,
)

/**
 * 档案检查：只统计真实存在的文件与真实任务状态。
 * 缺失文件会列出来，可以一键重新排队下载，不会静默丢记录。
 */
@Composable
fun ArchiveCheckScreen(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by AppState.account.collectAsState()
    var state by remember { mutableStateOf(CheckState()) }
    var running by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    suspend fun runCheck() {
        val acc = account ?: return
        running = true
        state = withContext(Dispatchers.IO) {
            val db = Db.get(context)
            val stats = db.stats(acc.id)
            val byState = db.mediaCountByState(acc.id)
            var missing = 0
            var emptyHash = 0
            var bytes = 0L
            val done = db.mediaMarkedDone(acc.id)
            done.forEach { row ->
                val f = File(row.localPath)
                if (row.localPath.isBlank() || !f.exists() || f.length() <= 0) missing++
                if (row.hash.isBlank()) emptyHash++
                bytes += row.bytes
            }
            CheckState(
                moments = stats.moments,
                comments = stats.comments,
                mediaTotal = stats.media,
                mediaOk = byState[2] ?: 0,
                mediaFailed = (byState[3] ?: 0) + (byState[4] ?: 0),
                missingFiles = missing,
                emptyHash = emptyHash,
                indexed = db.ftsCount(acc.id),
                unfinished = db.tasks(acc.id, limit = 100).count { !it.status.done },
                partial = db.momentCount(acc.id, RecoveryState.RECOVERED_PARTIAL) +
                    db.momentCount(acc.id, RecoveryState.RECOVERED_FULL),
                fragments = db.momentCount(acc.id, RecoveryState.INTERACTION_FRAGMENT),
                bytes = bytes,
                scanned = done.size,
            )
        }
        running = false
    }

    LaunchedEffect(account?.id) { runCheck() }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("档案检查", "本地文件与索引的真实状态", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                BoxCard {
                    val issues = state.missingFiles + state.emptyHash + state.unfinished
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (running) "正在检查" else if (issues == 0) "档案正常" else "发现 $issues 个待处理",
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier.size(8.dp).clip(CircleShape)
                                .background(if (issues == 0) c.green else c.amber)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Stat("内容", "${state.moments}")
                        Stat("评论", "${state.comments}")
                        Stat("媒体", "${state.mediaOk}/${state.mediaTotal}")
                        Stat("索引", "${state.indexed}")
                    }
                    if (note.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(note, fontSize = 11.sp, color = c.green)
                    }
                }
            }

            item {
                SectionLabel("检查项")
                BoxCard {
                    CheckRow("数据记录", state.moments > 0, "${state.moments} 条内容 · ${state.comments} 条评论")
                    Divider()
                    CheckRow(
                        "媒体文件",
                        state.missingFiles == 0,
                        if (state.scanned == 0) "还没有已保存的媒体"
                        else "已检查 ${state.scanned} 个 · ${state.missingFiles} 个丢失",
                    )
                    Divider()
                    CheckRow(
                        "文件校验",
                        state.emptyHash == 0,
                        if (state.emptyHash == 0) "已保存文件都有 SHA-256" else "${state.emptyHash} 个文件没有校验值",
                    )
                    Divider()
                    CheckRow(
                        "搜索索引",
                        state.indexed >= state.moments && state.moments > 0,
                        "${state.indexed} / ${state.moments} 条已建索引",
                    )
                    Divider()
                    CheckRow("归档任务", state.unfinished == 0, if (state.unfinished == 0) "没有未完成任务" else "${state.unfinished} 个未完成")
                    Divider()
                    CheckRow(
                        "历史内容",
                        true,
                        "${state.partial} 条已找回 · ${state.fragments} 条仅剩痕迹",
                    )
                }
            }

            item {
                SectionLabel("可以做什么")
                BoxCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton(if (running) "检查中" else "重新检查") {
                            scope.launch { runCheck() }
                        }
                        GhostButton("重建搜索索引") {
                            scope.launch {
                                val acc = account ?: return@launch
                                val n = withContext(Dispatchers.IO) { Db.get(context).reindexAccount(acc.id) }
                                note = "已重建 $n 条索引"
                                runCheck()
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("重新下载缺失媒体") {
                            scope.launch {
                                val acc = account ?: return@launch
                                withContext(Dispatchers.IO) {
                                    val db = Db.get(context)
                                    db.mediaRows(acc.id, null, true, 500, 0).forEach { db.updateMediaState(it.id, 0) }
                                    db.mediaMarkedDone(acc.id).forEach { row ->
                                        val f = File(row.localPath)
                                        if (row.localPath.isBlank() || !f.exists()) db.updateMediaState(row.id, 0)
                                    }
                                }
                                AppState.startTask(context, "media", "重新下载媒体")
                                note = "已重新排队，去归档记录查看进度"
                            }
                        }
                        GhostButton("导出档案") { onOpen(Route.SettingsExport) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "已保存 ${formatBytes(state.bytes)} 解压后媒体",
                        fontSize = 10.5.sp, color = c.secondary,
                    )
                }
            }

            item {
                Text(
                    "检查只读取本机文件，不上传任何数据",
                    fontSize = 10.5.sp, color = c.secondary,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(Modifier.width(78.dp)) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, fontSize = 10.sp, color = LocalMemora.current.secondary)
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, detail: String) {
    val c = LocalMemora.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) c.green else c.amber))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 10.5.sp, color = c.secondary)
        }
    }
}
