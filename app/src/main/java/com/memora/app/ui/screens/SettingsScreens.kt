package com.memora.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.produceState
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
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memora.app.BuildConfig
import com.memora.app.core.Prefs
import com.memora.app.core.UpdateCheck
import com.memora.app.core.UpdateInfo
import com.memora.app.core.Updater
import com.memora.app.core.SessionStore
import com.memora.app.core.db.Db
import com.memora.app.core.export.Exporter
import com.memora.app.core.task.Probe
import com.memora.app.core.task.TaskEngine
import com.memora.app.ui.AppState
import com.memora.app.ui.Route
import com.memora.app.ui.components.ArrowRow
import com.memora.app.ui.components.Badge
import com.memora.app.ui.components.BoxCard
import com.memora.app.ui.components.Divider
import com.memora.app.ui.components.DropRow
import com.memora.app.ui.components.EntryRow
import com.memora.app.ui.components.GhostButton
import com.memora.app.ui.components.PrimaryButton
import com.memora.app.ui.components.ScreenTitle
import com.memora.app.ui.components.SectionLabel
import com.memora.app.ui.components.ToggleRow
import com.memora.app.ui.components.formatBytes
import com.memora.app.ui.components.formatTime
import com.memora.app.ui.theme.LocalMemora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun SettingsHomeScreen(onOpen: (Route) -> Unit, onBack: (() -> Unit)? = null) {
    val c = LocalMemora.current
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("设置", "MEMORA ${BuildConfig.VERSION_NAME}", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                BoxCard {
                    EntryRow(icon = MiuixIcons.Lock, title = "账号与隐私", summary = "会话 加密 清除数据", tint = c.green, onClick = { onOpen(Route.SettingsAccount) })
                    Divider()
                    EntryRow(icon = MiuixIcons.Scan, title = "归档与范围", summary = "范围 年份 来源", tint = c.blue, onClick = { onOpen(Route.SettingsRecovery) })
                    Divider()
                    EntryRow(icon = MiuixIcons.Tasks, title = "后台归档", summary = "网络 重试 通知", tint = c.amber, onClick = { onOpen(Route.SettingsTasks) })
                    Divider()
                    EntryRow(icon = MiuixIcons.Folder, title = "存储与媒体", summary = "占用 缓存 清理", tint = c.green, onClick = { onOpen(Route.SettingsStorage) })
                    Divider()
                    EntryRow(icon = MiuixIcons.Theme, title = "外观与交互", summary = "主题 动效 材质", tint = c.blue, onClick = { onOpen(Route.SettingsAppearance) })
                    Divider()
                    EntryRow(icon = MiuixIcons.Import, title = "导出与迁移", summary = "ZIP HTML CSV", tint = c.amber, onClick = { onOpen(Route.SettingsExport) })
                    Divider()
                    EntryRow(icon = MiuixIcons.Info, title = "版本与说明", summary = "作者 版本 开源 诊断", tint = c.secondary, onClick = { onOpen(Route.About) })
                }
            }
            item {
                Text(
                    "MEMORA 只做本地归档 不上传任何数据",
                    fontSize = 11.sp, color = c.secondary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
fun SettingsAccountScreen(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by AppState.account.collectAsState()
    val hideUnavailable by Prefs.hideUnavailable.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("账号与隐私", "会话只在本机", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp)) {
            item {
                BoxCard {
                    Text(account?.nickname?.ifBlank { "未连接" } ?: "未连接", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (account != null) "QQ ${account?.uin}" else "登录后才会产生档案",
                        fontSize = 12.sp, color = c.secondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("会话保存于 ${formatTime(SessionStore.savedAt(context))}", fontSize = 11.sp, color = c.secondary)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton(if (account == null) "连接账号" else "重新登录") { onOpen(Route.Login) }
                        GhostButton("清除会话") {
                            SessionStore.clear(context)
                            AppState.notify("会话已清除 档案仍然保留")
                        }
                    }
                }
            }
            item {
                SectionLabel("隐私")
                BoxCard {
                    ToggleRow(
                        "隐藏未找到的内容",
                        "浏览时不显示证据不足的条目 数据仍然保留",
                        hideUnavailable,
                    ) { Prefs.setHideUnavailable(it) }
                    Divider()
                    ArrowRow("导出与迁移", "导出包不包含 Cookie 与登录凭证") { onOpen(Route.SettingsExport) }
                }
            }
            item {
                SectionLabel("危险操作")
                BoxCard {
                    Text(
                        "清除档案会删除本机上这个账号的好友快照 说说 评论 媒体与任务记录 不可恢复",
                        fontSize = 12.sp, color = c.secondary, lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    GhostButton("清除本机档案") { confirmClear = true }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (confirmClear) {
        OverlayDialog(
            show = true,
            title = "确认清除",
            summary = "将删除本机全部档案数据",
            onDismissRequest = { confirmClear = false },
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    "只影响本机 不会修改你的 QQ 空间。清除后需要重新扫描才能恢复内容。",
                    fontSize = 12.5.sp, color = c.secondary, lineHeight = 19.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("取消") { confirmClear = false }
                    GhostButton("确认清除") {
                        scope.launch {
                            val acc = account
                            if (acc != null) {
                                withContext(Dispatchers.IO) { Db.get(context).clearAccount(acc.id) }
                                AppState.reload(context)
                            }
                            confirmClear = false
                            AppState.notify("本机档案已清除")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsCaptureScreen(onBack: () -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val years = listOf("尽量到底", "最近 3 年", "最近 5 年", "2018 年起", "2013 年起")
    val yearValues = listOf(0, 3, 5, 2018, 2013)
    var yearIdx by remember { mutableStateOf(yearValues.indexOf(Prefs.scanStartYear.value).coerceAtLeast(0)) }
    val maxPages by Prefs.maxPages.collectAsState()
    val pageSize by Prefs.pageSize.collectAsState()
    val gap by Prefs.requestGapMs.collectAsState()
    val wifi by Prefs.wifiOnly.collectAsState()
    val foreground by Prefs.foregroundOnly.collectAsState()
    val autoResume by Prefs.autoResume.collectAsState()
    val media by Prefs.downloadMedia.collectAsState()
    var probing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("归档与范围", "这些选项会改变实际请求", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp)) {
            item {
                BoxCard {
                    ToggleRow("说说", "当前可见的说说与评论", Prefs.includeMoments.collectAsState().value) { Prefs.setIncludeMoments(it) }
                    Divider()
                    ToggleRow("历史内容", "更早的记录与互动残留", Prefs.includeHistory.collectAsState().value) { Prefs.setIncludeHistory(it) }
                    Divider()
                    ToggleRow("相册与视频", "相册结构 图片 视频", Prefs.includeAlbums.collectAsState().value) { Prefs.setIncludeAlbums(it) }
                    Divider()
                    ToggleRow("好友档案", "好友快照与关系变化", Prefs.includeFriends.collectAsState().value) { Prefs.setIncludeFriends(it) }
                }
            }
            item {
                BoxCard {
                    DropRow("起始年份", years, yearIdx) {
                        yearIdx = it
                        Prefs.setScanStartYear(yearValues[it])
                    }
                    Divider()
                    DropRow("每个来源最多翻页", listOf("10", "20", "40", "80", "150"), (maxPages / 10 - 1).coerceIn(0, 4)) {
                        Prefs.setMaxPages(listOf(10, 20, 40, 80, 150)[it])
                    }
                    Divider()
                    DropRow("每页条数", listOf("10", "20", "30", "50"), listOf(10, 20, 30, 50).indexOf(pageSize).coerceAtLeast(0)) {
                        Prefs.setPageSize(listOf(10, 20, 30, 50)[it])
                    }
                    Divider()
                    DropRow("请求间隔", listOf("0.6 秒", "1.2 秒", "2 秒", "4 秒"), listOf(600, 1200, 2000, 4000).indexOf(gap).coerceAtLeast(0)) {
                        Prefs.setRequestGapMs(listOf(600, 1200, 2000, 4000)[it])
                    }
                }
            }
            item {
                SectionLabel("策略")
                BoxCard {
                    ToggleRow("仅 Wi-Fi 下载", "移动网络下任务会停在等待状态", wifi) { Prefs.setWifiOnly(it) }
                    Divider()
                    ToggleRow("仅前台运行", "离开应用即暂停 回来继续", foreground) { Prefs.setForegroundOnly(it) }
                    Divider()
                    ToggleRow("启动后自动续跑", "上次中断的任务自动继续", autoResume) { Prefs.setAutoResume(it) }
                    Divider()
                    ToggleRow("下载图片与视频", "媒体文件保存在应用专属目录", media) { Prefs.setDownloadMedia(it) }
                }
            }
            item {
                SectionLabel("来源")
                BoxCard {
                    Text(
                        "检查会发一次请求确认登录状态，不会写入档案",
                        fontSize = 11.5.sp, color = c.secondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "每个来源的结果在归档页逐条显示",
                        fontSize = 11.5.sp, color = c.secondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.padding(vertical = 6.dp)) {
                        GhostButton(if (probing) "探测中" else "探测全部来源") {
                            val acc = AppState.account.value ?: return@GhostButton
                            probing = true
                            scope.launch {
                                withContext(Dispatchers.IO) { Probe.all(context, acc.id) }
                                AppState.reload(context)
                                probing = false
                                AppState.notify("来源探测完成")
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "游标按来源独立保存",
                    fontSize = 10.5.sp, color = c.secondary, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
fun SettingsTaskNetworkScreen(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val autoResume by Prefs.autoResume.collectAsState()
    val foreground by Prefs.foregroundOnly.collectAsState()
    val notify by Prefs.notifyDone.collectAsState()
    val tasks by AppState.tasks.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("后台归档", "离开应用也能继续", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp)) {
            item {
                BoxCard {
                    ToggleRow("完成后通知", "任务结束时发一条系统通知", notify) { Prefs.setNotifyDone(it) }
                    Divider()
                    ToggleRow("自动续跑", "启动应用时继续未完成任务", autoResume) { Prefs.setAutoResume(it) }
                    Divider()
                    ToggleRow("仅前台运行", "不在后台消耗流量", foreground) { Prefs.setForegroundOnly(it) }
                }
            }
            item {
                SectionLabel("正在运行", "${TaskEngine.runningIds.value.size} 个")
                BoxCard {
                    val running = tasks.filter { it.status.active }
                    if (running.isEmpty()) {
                        Text("当前没有运行中的任务", fontSize = 12.5.sp, color = c.secondary)
                    } else {
                        running.forEachIndexed { i, t ->
                            if (i > 0) Divider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(t.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                GhostButton("暂停") { TaskEngine.pause(context, t.id) }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    GhostButton("任务中心") { onOpen(Route.Tasks) }
                }
            }
            item {
                Spacer(Modifier.height(10.dp))
                Text(
                    "长任务通过前台服务保持运行 通知栏会显示进度 系统回收进程后可从检查点继续",
                    fontSize = 11.sp, color = LocalMemora.current.secondary, lineHeight = 17.sp,
                )
            }
        }
    }
}

@Composable
fun SettingsStorageScreen(onBack: () -> Unit, onOpen: (Route) -> Unit = {}) {
    val c = LocalMemora.current
    val context = LocalContext.current
    var mediaBytes by remember { mutableStateOf(0L) }
    var dbBytes by remember { mutableStateOf(0L) }
    var diagBytes by remember { mutableStateOf(0L) }
    var reload by remember { mutableStateOf(0) }
    val stats by AppState.stats.collectAsState()

    LaunchedEffect(reload) {
        withContext(Dispatchers.IO) {
            fun size(f: java.io.File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else (f.listFiles()?.sumOf { size(it) } ?: 0L)
            mediaBytes = size(java.io.File(context.filesDir, "archive/media"))
            dbBytes = context.getDatabasePath("memora_archive.db").let { size(it) + size(java.io.File(it.path + "-wal")) }
            diagBytes = size(java.io.File(context.filesDir, "diagnostics"))
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("存储与媒体", "全部在应用专属目录", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp)) {
            item {
                BoxCard {
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text(formatBytes(mediaBytes), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Text("媒体文件", fontSize = 11.sp, color = c.secondary)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(formatBytes(dbBytes), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Text("数据库", fontSize = 11.sp, color = c.secondary)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(formatBytes(diagBytes), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Text("诊断样本", fontSize = 11.sp, color = c.secondary)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "已下载 ${stats.media} 个媒体 · 累计 ${formatBytes(stats.mediaBytes)}",
                        fontSize = 11.5.sp, color = c.secondary,
                    )
                }
            }
            item {
                SectionLabel("档案")
                BoxCard {
                    ArrowRow("档案检查", "文件 索引 任务 的真实状态") { onOpen(Route.ArchiveCheck) }
                    Divider()
                    ArrowRow("照片与视频", "浏览已保存的本地文件") { onOpen(Route.Media) }
                    Divider()
                    ArrowRow("备份与导出", "ZIP 含离线 HTML") { onOpen(Route.SettingsExport) }
                }
            }
            item {
                SectionLabel("维护")
                BoxCard {
                    ArrowRow("清理诊断样本", "接口结构排查用的临时文本") {
                        java.io.File(context.filesDir, "diagnostics").deleteRecursively()
                        reload++
                        AppState.notify("诊断样本已清理")
                    }
                    Divider()
                    ArrowRow("删除已下载媒体", "记录保留 只删文件与完成状态") {
                        val n = Db.get(context).resetDownloadedMedia()
                        java.io.File(context.filesDir, "archive/media").deleteRecursively()
                        reload++
                        AppState.notify("已重置 $n 个媒体文件")
                    }
                    Divider()
                    ArrowRow("清理未完成的临时下载", "只删除 .part 中间文件") {
                        java.io.File(context.filesDir, "archive/media").listFiles()
                            ?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                        reload++
                        AppState.notify("临时文件已清理")
                    }
                }
            }
            item {
                Text(
                    "媒体与数据库都在应用私有目录 卸载应用会一并删除 需要长期保存请导出档案包",
                    fontSize = 11.sp, color = c.secondary, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
fun SettingsAppearanceScreen(onBack: () -> Unit) {
    val theme by Prefs.themeMode.collectAsState()
    val feedback by Prefs.cardFeedback.collectAsState()
    val reduce by Prefs.reduceMotion.collectAsState()
    val premium by Prefs.premiumMaterial.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("外观与交互", "设置立即生效", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp)) {
            item {
                BoxCard {
                    DropRow("主题", listOf("跟随系统", "浅色", "深色"), theme) { Prefs.setThemeMode(it) }
                    Divider()
                    DropRow("卡片按压反馈", listOf("Sink 下沉", "Tilt 倾斜", "关闭"), feedback) { Prefs.setCardFeedback(it) }
                    Divider()
                    ToggleRow("减少动效", "关闭转场与装饰动画 保留状态反馈", reduce) { Prefs.setReduceMotion(it) }
                    Divider()
                    ToggleRow("高级材质", "页面柔光与半透明卡片", premium) { Prefs.setPremiumMaterial(it) }
                }
            }
            item {
                Text(
                    "减少动效会同时降低转场时长 但预测性返回手势仍然可用",
                    fontSize = 11.sp, color = LocalMemora.current.secondary, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
fun SettingsExportScreen(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by AppState.account.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf("") }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        val acc = account ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        Exporter.exportZip(context, Db.get(context), acc.id, out)
                    }
                }.getOrNull()
            }
            busy = false
            lastResult = if (summary == null) "导出失败 请检查存储权限"
            else "已导出 ${summary.moments} 条内容 ${summary.friends} 位好友 ${summary.mediaFiles} 个媒体文件"
            AppState.notify(lastResult)
            if (summary != null) {
                withContext(Dispatchers.IO) {
                    Db.get(context).log("INFO", "export", lastResult)
                }
            }
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        val acc = account ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        Exporter.exportMomentsCsv(Db.get(context), acc.id, it)
                    }
                }.isSuccess
            }
            busy = false
            lastResult = if (ok) "CSV 已导出" else "CSV 导出失败"
            AppState.notify(lastResult)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("导出与迁移", "不联网 直接从本地数据库生成", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp)) {
            item {
                BoxCard {
                    Text("完整档案包", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "ZIP 内含 moments.json friends.json 媒体副本 离线 HTML 浏览器与 manifest 校验清单。" +
                            "HTML 不依赖任何 CDN 断网可读",
                        fontSize = 12.sp, color = c.secondary, lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(if (busy) "正在导出" else "导出 ZIP", enabled = account != null && !busy) {
                        zipLauncher.launch(Exporter.suggestions(account?.uin ?: "archive"))
                    }
                }
            }
            item {
                SectionLabel("轻量导出")
                BoxCard {
                    ArrowRow("说说表格 CSV", "适合在表格软件里筛选") {
                        csvLauncher.launch("MEMORA-${account?.uin ?: "archive"}.csv")
                    }
                    Divider()
                    ArrowRow("导入旧档案", "从 JSON / ZIP 合并进现有档案") { onOpen(Route.ImportArchive) }
                }
            }
            item {
                SectionLabel("说明")
                BoxCard {
                    Text(
                        "导出包不含 Cookie 与任何登录凭证。迁移到新设备时先登录再导入，避免来源丢失。",
                        fontSize = 12.sp, color = c.secondary, lineHeight = 19.sp,
                    )
                }
            }
            if (lastResult.isNotBlank()) {
                item { Text(lastResult, fontSize = 12.sp, color = c.ok, modifier = Modifier.padding(top = 10.dp)) }
            }
        }
    }
}

@Composable
fun SettingsDiagnosticScreen(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    AboutScreen(onBack = onBack, onOpen = onOpen)
}

@Composable
private fun LegacyDiagnosticScreen(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val account by AppState.account.collectAsState()
    val sources by AppState.sources.collectAsState()
    val stats by AppState.stats.collectAsState()
    var logs by remember { mutableStateOf<List<Triple<Long, String, String>>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        logs = withContext(Dispatchers.IO) { Db.get(context).logs(80) }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("诊断与关于", "全部是真实读取的值", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp)) {
            item {
                BoxCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val logo = com.memora.app.ui.components.rememberDrawableBitmap(com.memora.app.R.drawable.ic_launcher_fg)
                        if (logo != null) {
                            androidx.compose.foundation.Image(
                                bitmap = logo, contentDescription = null,
                                modifier = Modifier.size(46.dp),
                            )
                        } else {
                            Box(Modifier.size(46.dp).clip(CircleShape).background(c.hairline))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("MEMORA", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                            Text("Personal Digital Memory Archive", fontSize = 11.sp, color = c.secondary)
                        }
                        Badge("v${BuildConfig.VERSION_NAME}", c.accent)
                    }
                    Spacer(Modifier.height(12.dp))
                    Divider()
                    Row(Modifier.padding(top = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${stats.moments}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text("内容", fontSize = 10.5.sp, color = c.secondary)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("${stats.friends}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text("好友", fontSize = 10.5.sp, color = c.secondary)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("${stats.comments}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text("评论", fontSize = 10.5.sp, color = c.secondary)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("${stats.activities}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text("互动", fontSize = 10.5.sp, color = c.secondary)
                        }
                    }
                }
            }
            item {
                SectionLabel("数据源状态", "${sources.size} 条")
                BoxCard {
                    if (sources.isEmpty()) {
                        Text("还没有探测记录", fontSize = 12.sp, color = c.secondary)
                    } else {
                        sources.forEachIndexed { i, s ->
                            if (i > 0) Divider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${s.sourceId} · ${s.stream}", fontSize = 12.5.sp)
                                    Text(
                                        s.detail.ifBlank { s.status.detail },
                                        fontSize = 10.5.sp, color = c.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Badge(s.status.label, statusTone(s.status, c))
                            }
                        }
                    }
                }
            }
            item {
                SectionLabel("运行日志", "${logs.size} 条")
                BoxCard {
                    if (logs.isEmpty()) {
                        Text("没有日志", fontSize = 12.sp, color = c.secondary)
                    } else {
                        logs.take(30).forEachIndexed { i, (at, level, msg) ->
                            if (i > 0) Divider()
                            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                Text("$level · ${formatTime(at)}", fontSize = 10.sp, color = if (level == "ERROR") c.danger else c.secondary)
                                Text(msg, fontSize = 11.5.sp, lineHeight = 17.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("刷新") { reload++ }
                        GhostButton("清空日志") { AppState.notify("日志会在下次启动时轮转") }
                    }
                }
            }
            item {
                SectionLabel("最近一次崩溃")
                BoxCard {
                    val crash = remember { com.memora.app.core.Crash.last(context) }
                    if (crash.isNullOrBlank()) {
                        Text("没有崩溃记录", fontSize = 12.sp, color = c.secondary)
                    } else {
                        Text(
                            crash.take(600),
                            fontSize = 10.sp, color = c.danger, lineHeight = 15.sp, maxLines = 12,
                        )
                        Spacer(Modifier.height(8.dp))
                        GhostButton("清除记录") {
                            com.memora.app.core.Crash.clear(context)
                            reload++
                        }
                    }
                }
            }
            item {
                SectionLabel("关于")
                BoxCard {
                    ArrowRow("版本与说明", "MEMORA ${BuildConfig.VERSION_NAME}") { onOpen(Route.About) }
                    Divider()
                    ArrowRow("开源许可", "依赖与参考项目") { onOpen(Route.Licenses) }
                }
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val account by AppState.account.collectAsState()
    val stats by AppState.stats.collectAsState()
    val sources by AppState.sources.collectAsState()
    var open by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf<List<Triple<Long, String, String>>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var sourceOpen by remember { mutableStateOf(false) }
    var sourceText by remember { mutableStateOf(Prefs.updateSource.value) }
    val scope2 = rememberCoroutineScope()
    val crash = remember(reload) { com.memora.app.core.Crash.last(context) }
    val icon = com.memora.app.ui.components.rememberDrawableBitmap(com.memora.app.R.drawable.ic_launcher_fg)
    val avatar = com.memora.app.ui.components.rememberDrawableBitmap(com.memora.app.R.drawable.author_avatar)

    LaunchedEffect(reload) {
        logs = withContext(Dispatchers.IO) { runCatching { Db.get(context).logs(40) }.getOrDefault(emptyList()) }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("关于", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 软件信息
            item {
                BoxCard {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (icon != null) {
                            androidx.compose.foundation.Image(
                                bitmap = icon, contentDescription = "MEMORA",
                                modifier = Modifier.size(64.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = androidx.compose.ui.text.buildAnnotatedString {
                                withStyle(
                                    androidx.compose.ui.text.SpanStyle(
                                        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                            listOf(c.green, c.blue)
                                        ),
                                        fontSize = 25.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 3.sp,
                                    )
                                ) { append("MEMORA") }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                            fontSize = 11.5.sp, color = c.secondary,
                        )
                        Text(
                            "编译 ${BuildConfig.BUILD_TIME}",
                            fontSize = 11.sp, color = c.secondary,
                        )
                    }
                }
            }

            // 贡献者
            item { SectionLabel("贡献者") }
            item {
                BoxCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(c.hairline),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (avatar != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = avatar, contentDescription = null,
                                    modifier = Modifier.size(46.dp).clip(CircleShape),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            } else {
                                Text("青", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.secondary)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("青", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("开发者", fontSize = 11.sp, color = c.secondary)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("设计初衷 · 把回忆留在每个人的手中", fontSize = 11.5.sp, color = c.secondary)
                }
            }

            // 其他
            item { SectionLabel("其他") }
            item {
                BoxCard {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            scope2.launch {
                                note = "正在检查更新"
                                val r = withContext(Dispatchers.IO) { Updater.check(context) }
                                when (r) {
                                    is UpdateCheck.Available -> update = r.info
                                    is UpdateCheck.Latest -> note = "已是最新版本"
                                    is UpdateCheck.Failed -> note = "检查失败 " + r.reason
                                    is UpdateCheck.NotConfigured -> note = r.reason
                                }
                            }
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("检查更新", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("v${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = c.secondary)
                    }
                    Divider()
                    Row(
                        Modifier.fillMaxWidth().clickable { open = if (open == "license") "" else "license" }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("开源与许可", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(if (open == "license") "收起" else "展开", fontSize = 11.sp, color = c.secondary)
                    }
                    if (open == "license") {
                        Spacer(Modifier.height(4.dp))
                        listOf(
                            "Jetpack Compose · Apache-2.0",
                            "Miuix · Apache-2.0",
                            "OkHttp · Apache-2.0",
                            "jsoup · MIT",
                            "kotlinx.serialization · Apache-2.0",
                            "Kotlin Coroutines · Apache-2.0",
                        ).forEach { Text(it, fontSize = 11.5.sp, color = c.secondary, lineHeight = 21.sp) }
                        Spacer(Modifier.height(8.dp))
                    }
                    Divider()
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            scope2.launch {
                                val acc = account ?: return@launch
                                val n = withContext(Dispatchers.IO) {
                                    runCatching { Db.get(context).reindexAccount(acc.id) }.getOrDefault(0)
                                }
                                note = "已重建 $n 条索引"
                                reload++
                            }
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("重建搜索索引", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("${stats.moments} 条", fontSize = 11.sp, color = c.secondary)
                    }
                    Divider()
                    Row(
                        Modifier.fillMaxWidth().clickable { open = if (open == "diag") "" else "diag" }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("诊断", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(
                            if (crash.isNullOrBlank()) "正常" else "有崩溃记录",
                            fontSize = 11.sp,
                            color = if (crash.isNullOrBlank()) c.secondary else c.danger,
                        )
                    }
                    if (open == "diag") {
                        Spacer(Modifier.height(6.dp))
                        Text("查找来源", fontSize = 11.sp, color = c.secondary)
                        sources.take(6).forEach { s ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(statusTone(s.status, c)))
                                Spacer(Modifier.width(8.dp))
                                Text("${s.sourceId} · ${s.stream}", fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Text(s.status.label, fontSize = 10.sp, color = c.secondary)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (!crash.isNullOrBlank()) {
                            Text("最近一次崩溃", fontSize = 11.sp, color = c.secondary)
                            Text(crash.take(400), fontSize = 9.5.sp, color = c.danger, lineHeight = 14.sp, maxLines = 8)
                            Spacer(Modifier.height(6.dp))
                            GhostButton("清除崩溃记录") {
                                com.memora.app.core.Crash.clear(context)
                                reload++
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("日志", fontSize = 11.sp, color = c.secondary)
                        logs.take(5).forEach { (at, level, msg) ->
                            Text(
                                "${formatTime(at)} $level $msg",
                                fontSize = 9.5.sp, color = c.secondary, maxLines = 2, lineHeight = 14.sp,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        GhostButton("刷新") { reload++ }
                    }
                    Divider()
                    Row(
                        Modifier.fillMaxWidth().clickable { sourceOpen = true }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("更新源", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(
                            Prefs.updateSource.value.ifBlank { "默认" },
                            fontSize = 11.sp, color = c.secondary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (note.isNotBlank()) {
                item { Text(note, fontSize = 11.sp, color = c.secondary) }
            }
            item {
                Text(
                    "内容只保存在本机 · 不上传 · 可随时导出",
                    fontSize = 10.5.sp, color = c.secondary,
                )
            }
        }
    }

    update?.let { info -> UpdateDialog(info) { update = null } }

    if (sourceOpen) {
        OverlayDialog(
            show = true, title = "更新源", summary = "填写 update.json 的完整地址",
            onDismissRequest = { sourceOpen = false },
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                TextField(
                    value = sourceText,
                    onValueChange = { sourceText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "https://…/update.json",
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("清空") {
                        sourceText = ""
                        Prefs.setUpdateSource("")
                        sourceOpen = false
                        note = "已改回内置源"
                    }
                    GhostButton("保存") {
                        Prefs.setUpdateSource(sourceText.trim())
                        sourceOpen = false
                        note = "更新源已保存"
                    }
                }
            }
        }
    }
}

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val c = LocalMemora.current
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("开源许可", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp)) {
            item {
                BoxCard {
                    listOf(
                        "Jetpack Compose · Apache-2.0",
                        "Miuix (compose-miuix-ui) · Apache-2.0",
                        "OkHttp · Apache-2.0",
                        "jsoup · MIT",
                        "kotlinx.serialization · Apache-2.0",
                        "Kotlin Coroutines · Apache-2.0",
                    ).forEach { Text(it, fontSize = 12.5.sp, lineHeight = 22.sp) }
                }
            }
            item {
                SectionLabel("参考项目")
                BoxCard {
                    Text(
                        "QQ-Zone-Restore-Archive · QzoneArchive · qzone-archiver · QZoneExport · " +
                            "QzoneTweetsBack · qzone-history · onebot-qzone",
                        fontSize = 12.sp, color = c.secondary, lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "只参考公开的接口路径 数据模型与任务设计。本项目没有复制上述仓库的源码，" +
                            "因此不继承其 GPL 义务；如后续需要直接复用代码 会先核对许可证再决定分发方式。",
                        fontSize = 11.5.sp, color = c.secondary, lineHeight = 18.sp,
                    )
                }
            }
            item {
                Text(
                    "https://github.com/compose-miuix-ui/miuix",
                    fontSize = 11.sp, color = c.secondary, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}
