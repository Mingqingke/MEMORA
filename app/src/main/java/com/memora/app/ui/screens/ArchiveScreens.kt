package com.memora.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memora.app.core.Activity
import com.memora.app.core.Comment
import com.memora.app.core.Completeness
import com.memora.app.core.Confidence
import com.memora.app.core.EvidenceRow
import com.memora.app.core.MediaItem
import com.memora.app.core.Moment
import com.memora.app.core.RecoveryState
import com.memora.app.core.db.Db
import com.memora.app.core.recovery.RecoveryEngine
import com.memora.app.core.Prefs
import com.memora.app.ui.AppState
import com.memora.app.ui.Route
import com.memora.app.ui.components.Badge
import com.memora.app.ui.components.BoxCard
import com.memora.app.ui.components.Chip
import com.memora.app.ui.components.Divider
import com.memora.app.ui.components.Dots
import com.memora.app.ui.components.EmptyBlock
import com.memora.app.ui.components.KeyValue
import com.memora.app.ui.components.ScreenTitle
import com.memora.app.ui.components.SectionLabel
import com.memora.app.ui.components.YearBars
import com.memora.app.ui.components.GhostButton
import com.memora.app.ui.components.formatBytes
import com.memora.app.ui.components.formatTime
import com.memora.app.ui.theme.LocalMemora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun ArchiveScreen(onOpen: (Route) -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val account by AppState.account.collectAsState()
    val stats by AppState.stats.collectAsState()
    var year by remember { mutableStateOf<Int?>(null) }
    var state by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("all") }
    var list by remember { mutableStateOf<List<Moment>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var pickYear by remember { mutableStateOf(false) }
    var years by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }
    var nameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var avatarMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(account?.id, year, state, source, stats.moments) {
        val acc = account ?: return@LaunchedEffect
        loading = true
        years = withContext(Dispatchers.IO) {
            runCatching { Db.get(context).yearCounts(acc.id) }.getOrDefault(emptyList())
        }
        list = withContext(Dispatchers.IO) {
            runCatching {
                Db.get(context).moments(
                    acc.id, year = year, state = state.ifBlank { null },
                    limit = 300, source = if (source == "all") null else source,
                )
            }.getOrDefault(emptyList())
        }
        withContext(Dispatchers.IO) {
            runCatching {
            val db = Db.get(context)
            val names = HashMap<String, String>()
            val avatars = HashMap<String, String>()
            list.map { it.authorUin }.distinct().take(200).forEach { uin ->
                if (uin.isBlank()) return@forEach
                names[uin] = db.displayName(acc.id, uin)
                avatars[uin] = db.friend(acc.id, uin)?.avatar.orEmpty()
            }
            nameMap = names
            avatarMap = avatars
            }
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(
            "档案",
            if (year == null) "${stats.moments} 条内容" else "$year 年 · ${list.size} 条",
            actions = {
                Text(
                    "搜索", fontSize = 13.sp, color = c.secondary,
                    modifier = Modifier.clickable { onOpen(Route.Search) }.padding(8.dp),
                )
            },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (stats.years.isNotEmpty() || year != null) {
                item {
                    BoxCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("年度分布", fontSize = 12.5.sp, color = c.secondary, modifier = Modifier.weight(1f))
                            Text(
                                if (year == null) "选择年份" else "$year · 清除",
                                fontSize = 11.sp, color = c.secondary,
                                modifier = Modifier.clickable {
                                    if (year == null) pickYear = true else year = null
                                }.padding(4.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        YearBars(stats.years.map { it.year to it.count }, year) { year = it }
                        if (stats.years.isEmpty()) {
                            Text(
                                "选中这年后还没有内容",
                                fontSize = 10.5.sp, color = c.secondary,
                            )
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(
                        "" to "全部",
                        "CURRENT" to "当前可见",
                        "INTERACTION_FRAGMENT" to "历史残留",
                        "RECOVERED_PARTIAL" to "部分恢复",
                        "UNAVAILABLE" to "待补齐",
                    ).forEach { (k, v) -> Chip(v, state == k) { state = k } }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(
                        "all" to "全部来源",
                        "current" to "在线",
                        "interaction" to "历史记录",
                        "local" to "本地导入",
                    ).forEach { (k, v) -> Chip(v, source == k) { source = k } }
                }
            }
            if (account == null) {
                item { EmptyBlock("尚未连接账号", "登录后才能浏览自己的档案") }
            } else if (list.isEmpty()) {
                item {
                    EmptyBlock(
                        if (loading) "正在读取" else if (year != null || state.isNotBlank()) "这个筛选下没有内容" else "还没有归档内容",
                        if (year != null || state.isNotBlank()) "换个年份或状态试试" else "完成一次归档后 内容会按时间出现在这里",
                    )
                }
            } else {
                items(list, key = { it.id }) { m ->
                    BoxCard(
                        modifier = Modifier.animateItem(),
                        onClick = { onOpen(Route.MomentDetail(m.tid)) },
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Badge(m.state.label, stateTone(m.state, c))
                                    Spacer(Modifier.width(8.dp))
                                    Text(formatTime(m.createTime), fontSize = 10.5.sp, color = c.secondary)
                                }
                                val authorName = nameMap[m.authorUin] ?: m.authorUin
                                val authorAvatar = avatarMap[m.authorUin].orEmpty()
                                if (m.authorUin.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        com.memora.app.ui.components.Avatar(
                                            uin = m.authorUin,
                                            rawUrl = authorAvatar,
                                            size = 20.dp,
                                            fallbackText = authorName,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(authorName, fontSize = 11.sp, color = c.secondary)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    m.content.ifBlank { "正文未找到 只剩互动痕迹" },
                                    fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                                    color = if (m.content.isBlank()) c.secondary else c.ink,
                                    lineHeight = 21.sp,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    listOfNotNull(
                                        if (m.commentCount > 0) "${m.commentCount} 评论" else null,
                                        if (m.activityCount > 0) "${m.activityCount} 互动" else null,
                                        if (m.mediaCount > 0) "${m.mediaCount} 媒体" else null,
                                        "${m.fieldCount} 个字段",
                                    ).joinToString(" · "),
                                    fontSize = 10.5.sp, color = c.secondary,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Dots(m.completeness.dots, c.accent)
                                Spacer(Modifier.height(4.dp))
                                Text(m.confidence.label, fontSize = 10.sp, color = c.secondary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickYear) {
        val years = (2005..java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)).toList().reversed()
        OverlayDialog(
            show = true,
            title = "选择年份",
            summary = "没有内容的年份也可以选",
            onDismissRequest = { pickYear = false },
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                LazyColumn(Modifier.height(300.dp)) {
                    items(years.chunked(3)) { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { y ->
                                val has = stats.years.any { it.year == y }
                                Box(Modifier.weight(1f)) {
                                    Chip(
                                        if (has) "$y" else "$y",
                                        selected = year == y,
                                    ) { year = y; pickYear = false }
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                GhostButton("全部年份") { year = null; pickYear = false }
            }
        }
    }
}

@Composable
fun MomentDetailScreen(tid: String, onBack: () -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by AppState.account.collectAsState()
    var moment by remember { mutableStateOf<Moment?>(null) }
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var activities by remember { mutableStateOf<List<Activity>>(emptyList()) }
    var media by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var evidence by remember { mutableStateOf<List<EvidenceRow>>(emptyList()) }
    var gaps by remember { mutableStateOf<List<com.memora.app.core.FieldGap>>(emptyList()) }
    var sources by remember { mutableStateOf<List<String>>(emptyList()) }
    var authorName by remember { mutableStateOf("") }
    var authorAvatar by remember { mutableStateOf("") }
    var interactors by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loadingLikes by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    LaunchedEffect(tid, account?.id) {
        val acc = account ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val db = Db.get(context)
            val m = db.moment(acc.id, tid)
            moment = m
            if (m != null) {
                comments = db.comments(m.id)
                activities = db.activitiesFor(m.id)
                media = db.media(m.id)
                evidence = db.evidence(m.id)
                sources = db.momentFields(m.id).map { it.sourceId }.distinct()
                val fields = db.momentFields(m.id).map { it.field }.toSet()
                gaps = RecoveryEngine.verdict(m, fields, comments.size, activities.size, sources.toSet()).missing
                if (m.authorUin.isNotBlank()) {
                    authorName = db.displayName(acc.id, m.authorUin)
                    authorAvatar = db.friend(acc.id, m.authorUin)?.avatar.orEmpty()
                }
                interactors = db.interactorUins(m.id, 12)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("内容详情", moment?.tid ?: tid, onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                BoxCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge(moment?.state?.label ?: "未知", stateTone(moment?.state ?: RecoveryState.UNKNOWN, c))
                        Spacer(Modifier.width(8.dp))
                        Text(formatTime(moment?.createTime), fontSize = 11.5.sp, color = c.secondary)
                        Spacer(Modifier.weight(1f))
                        Dots(moment?.completeness?.dots ?: 1, c.accent)
                    }
                    if (moment?.authorUin?.isNotBlank() == true) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            com.memora.app.ui.components.Avatar(
                                uin = moment?.authorUin.orEmpty(), rawUrl = authorAvatar,
                                size = 22.dp, fallbackText = authorName,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(authorName, fontSize = 12.sp, color = c.secondary)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        moment?.content?.ifBlank { "正文未找到" } ?: "",
                        fontSize = 15.sp, lineHeight = 24.sp,
                        color = if (moment?.content.isNullOrBlank()) c.secondary else c.ink,
                    )
                    if (!moment?.location.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(moment?.location ?: "", fontSize = 11.5.sp, color = c.secondary)
                    }
                    Spacer(Modifier.height(12.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))
                    KeyValue("完整度", moment?.completeness?.label ?: "—")
                    KeyValue("可信度", moment?.confidence?.label ?: "—")
                    KeyValue("互动", "${comments.size} 评论 · ${activities.size} 条互动记录")
                    KeyValue("媒体", "${media.count { it.state == 2 }} 已下载 / ${media.size} 个")
                }
            }

            if (moment?.state == RecoveryState.INTERACTION_FRAGMENT || moment?.content.isNullOrBlank()) {
                item {
                    BoxCard {
                        Text("为什么没有正文", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.warm)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "只在互动记录里出现过 正文已不存在 保留的是它曾经存在的证据",
                            fontSize = 11.5.sp, color = c.secondary, lineHeight = 18.sp,
                        )
                    }
                }
            }

            if (gaps.isNotEmpty()) {
                item {
                    SectionLabel("缺失字段", "${gaps.size} 项")
                    BoxCard {
                        gaps.forEachIndexed { i, g ->
                            if (i > 0) Divider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(g.field, fontSize = 12.5.sp, modifier = Modifier.width(90.dp))
                                Text(g.reason, fontSize = 11.5.sp, color = c.secondary, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            if (media.isNotEmpty()) {
                item {
                    SectionLabel("媒体", "${media.size} 个")
                    BoxCard {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(media, key = { it.id }) { m ->
                                MediaThumb(m)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "媒体需运行下载任务 · 链接过期会标记失效",
                            fontSize = 10.sp, color = c.secondary,
                        )
                    }
                }
            }

            if (comments.isNotEmpty()) {
                item {
                    SectionLabel("评论", "${comments.size} 条")
                    BoxCard {
                        comments.forEachIndexed { i, cm ->
                            if (i > 0) Divider()
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Row {
                                    Text(cm.authorName.ifBlank { cm.authorUin }, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.width(6.dp))
                                    Text(formatTime(cm.createTime), fontSize = 10.sp, color = c.secondary)
                                }
                                Text(cm.content, fontSize = 13.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                }
            }

            if (moment != null) {
                item {
                    BoxCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("点赞名单", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "已取得 ${activities.count { it.type == "like" }} 人 · 平台可能还有更多",
                                    fontSize = 10.5.sp, color = c.secondary,
                                )
                            }
                            GhostButton(if (loadingLikes) "读取中" else "加载") {
                                val acc = account ?: return@GhostButton
                                val m = moment ?: return@GhostButton
                                loadingLikes = true
                                scope.launch {
                                    val page = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val cookie = com.memora.app.core.SessionStore.cookie(context)
                                            val uin = com.memora.app.core.SessionStore.uin(context)
                                            val gtk = com.memora.app.core.net.Net.gtk(
                                                com.memora.app.core.SessionStore.pSkey(context)
                                            )
                                            val ctx2 = com.memora.app.core.net.QzoneContext(
                                                app = context, accountId = acc.id, uin = uin,
                                                cookie = cookie, gtk = gtk,
                                                qzonetoken = com.memora.app.core.SessionStore.token(context),
                                            )
                                            com.memora.app.core.net.QzoneApi.likeList(
                                                ctx2, m.tid, m.authorUin.ifBlank { uin },
                                            )
                                        }.getOrNull()
                                    }
                                    if (page == null) {
                                        AppState.notify("点赞名单读取失败")
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            Db.get(context).addLikes(acc.id, m.tid, page.users, "interaction")
                                        }
                                        activities = withContext(Dispatchers.IO) {
                                            runCatching { Db.get(context).activitiesFor(m.id) }.getOrDefault(emptyList())
                                        }
                                        AppState.notify("已取得 ${page.users.size} 人" + if (page.total > page.users.size) "，共 ${page.total} 人" else "")
                                    }
                                    loadingLikes = false
                                }
                            }
                        }
                    }
                }
            }

            if (interactors.isNotEmpty()) {
                item {
                    SectionLabel("参与互动", "${interactors.size} 人")
                    BoxCard {
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(interactors) { (uin, name) ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    com.memora.app.ui.components.Avatar(
                                        uin = uin, rawUrl = "", size = 34.dp,
                                        fallbackText = name.ifBlank { uin },
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        name.ifBlank { uin }, fontSize = 9.5.sp,
                                        color = c.secondary, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.width(46.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (activities.isNotEmpty()) {
                item {
                    SectionLabel("互动痕迹", "${activities.size} 条")
                    BoxCard {
                        activities.forEachIndexed { i, a ->
                            if (i > 0) Divider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Badge(
                                    when (a.type) {
                                        "like" -> "点赞"; "comment" -> "评论"; "reply" -> "回复"; else -> a.type
                                    },
                                    c.accent,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(a.actorName.ifBlank { a.actorUin }, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(formatTime(a.time), fontSize = 10.sp, color = c.secondary)
                            }
                        }
                    }
                }
            }

            item {
                SectionLabel("证据链", "${evidence.size} 条字段来源")
                BoxCard {
                    if (evidence.isEmpty()) {
                        Text("没有字段级来源记录", fontSize = 12.sp, color = c.secondary)
                    } else {
                        val group = evidence.groupBy { it.sourceId }
                        group.forEach { (sourceId, rows) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        com.memora.app.core.source.SourceRegistry.names()[sourceId] ?: sourceId,
                                        fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        rows.map { RecoveryEngine.fieldLabels[it.field] ?: it.field }.distinct().joinToString(" "),
                                        fontSize = 11.5.sp, color = c.secondary,
                                    )
                                }
                                Text(formatTime(rows.maxOf { it.capturedAt }), fontSize = 10.sp, color = c.secondary)
                            }
                            Divider()
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "同一内容的字段可能来自不同来源",
                            fontSize = 10.sp, color = c.secondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaThumb(m: MediaItem) {
    val c = LocalMemora.current
    val ctx = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, m.id, m.localPath) {
        value = if (m.localPath.isNotBlank()) withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(m.localPath)?.asImageBitmap() }.getOrNull()
        } else null
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(92.dp).clip(RoundedCornerShape(12.dp)).background(c.hairline),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b != null) {
                Image(b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            } else {
                Text(
                    when (m.state) {
                        2 -> "文件缺失"; 4 -> "链接失效"; 3 -> "下载失败"; 1 -> "下载中"; else -> "待下载"
                    },
                    fontSize = 10.sp, color = c.secondary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (m.state == 2) formatBytes(m.bytes) else if (m.kind == 1) "视频" else "图片",
            fontSize = 10.sp, color = c.secondary,
        )
    }
}

@Composable
fun SearchScreen(onOpen: (Route) -> Unit, onBack: () -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val account by AppState.account.collectAsState()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Moment>>(emptyList()) }
    var friendHits by remember { mutableStateOf<List<com.memora.app.core.Friend>>(emptyList()) }
    var commentHits by remember { mutableStateOf<List<com.memora.app.core.Comment>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf("all") }
    val recent by Prefs.recentSearch.collectAsState()
    val keyword = q.trim()

    LaunchedEffect(keyword, account?.id, range) {
        val acc = account ?: return@LaunchedEffect
        if (keyword.isBlank()) {
            results = emptyList(); friendHits = emptyList(); commentHits = emptyList(); searched = false
            return@LaunchedEffect
        }
        loading = true
        delay(220)
        val now = System.currentTimeMillis()
        val from = when (range) {
            "1y" -> now - 365L * 24 * 3600 * 1000
            "3y" -> now - 3 * 365L * 24 * 3600 * 1000
            "old" -> null
            else -> null
        }
        val to = when (range) {
            "old" -> now - 3 * 365L * 24 * 3600 * 1000
            else -> null
        }
        withContext(Dispatchers.IO) {
            val db = Db.get(context)
            val indexed = db.searchIndexed(acc.id, keyword)
            results = if (indexed.isNotEmpty()) indexed else db.search(acc.id, keyword, from = from, to = to)
            friendHits = db.searchFriends(acc.id, keyword)
            commentHits = db.searchComments(acc.id, keyword)
        }
        searched = true
        loading = false
        if (keyword.length >= 2) Prefs.pushRecentSearch(keyword)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("搜索", if (searched) "${results.size + friendHits.size + commentHits.size} 个结果" else "说说 评论 好友", onBack = onBack)
        Box(Modifier.padding(horizontal = 16.dp)) {
            TextField(
                value = q,
                onValueChange = { q = it },
                modifier = Modifier.fillMaxWidth(),
                label = "关键词 年份 昵称",
                singleLine = true,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            listOf("all" to "全部", "1y" to "近一年", "3y" to "近三年", "old" to "更早").forEach { (k, v) ->
                Chip(v, range == k) { range = k }
            }
        }
        if (keyword.isBlank()) {
            if (recent.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    recent.take(5).forEach { r -> Chip(r, false) { q = r } }
                }
                Spacer(Modifier.height(8.dp))
            }
            EmptyBlock("输入关键词开始搜索", "支持正文 评论 好友昵称 内容 ID")
            return@Column
        }
        if (loading) {
            EmptyBlock("正在搜索", "")
            return@Column
        }
        if (results.isEmpty() && friendHits.isEmpty() && commentHits.isEmpty()) {
            EmptyBlock(if (searched) "没有匹配内容" else "输入关键词", "试试更短的关键词")
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (friendHits.isNotEmpty()) {
                item { Text("好友 ${friendHits.size}", fontSize = 11.sp, color = c.secondary) }
                items(friendHits.take(6), key = { "f-${it.uin}" }) { f ->
                    BoxCard(onClick = { onOpen(Route.FriendDetail(f.uin)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            com.memora.app.ui.components.Avatar(
                                uin = f.uin, rawUrl = f.avatar, size = 32.dp, fallbackText = f.displayName,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(highlight(f.displayName, keyword), fontSize = 13.5.sp)
                                Text(f.uin, fontSize = 10.sp, color = c.secondary)
                            }
                        }
                    }
                }
            }
            if (commentHits.isNotEmpty()) {
                item { Text("评论 ${commentHits.size}", fontSize = 11.sp, color = c.secondary) }
                items(commentHits.take(8), key = { "c-${it.id}" }) { cm ->
                    BoxCard(onClick = { onOpen(Route.MomentDetail(cm.momentId.substringAfter(":"))) }) {
                        Text(
                            "${cm.authorName.ifBlank { cm.authorUin }}：",
                            fontSize = 11.sp, color = c.secondary,
                        )
                        Text(highlight(cm.content, keyword), fontSize = 13.sp, lineHeight = 20.sp)
                        Text(formatTime(cm.createTime), fontSize = 10.sp, color = c.secondary)
                    }
                }
            }
            if (results.isNotEmpty()) {
                item { Text("说说 ${results.size}", fontSize = 11.sp, color = c.secondary) }
                items(results, key = { it.id }) { m ->
                    BoxCard(onClick = { onOpen(Route.MomentDetail(m.tid)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Badge(m.state.label, stateTone(m.state, c))
                            Spacer(Modifier.width(8.dp))
                            Text(formatTime(m.createTime), fontSize = 10.5.sp, color = c.secondary)
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            highlight(m.content, keyword),
                            fontSize = 13.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp,
                        )
                    }
                }
            }
        }
    }
}

/** 关键词高亮：只标真实命中的片段。 */
private fun highlight(text: String, keyword: String): androidx.compose.ui.text.AnnotatedString {
    if (keyword.isBlank()) return androidx.compose.ui.text.AnnotatedString(text)
    val color = androidx.compose.ui.graphics.Color(0xFF2F9F68)
    val lower = text.lowercase()
    val key = keyword.lowercase()
    val builder = androidx.compose.ui.text.AnnotatedString.Builder(text)
    var index = lower.indexOf(key)
    var guard = 0
    while (index >= 0 && guard++ < 40) {
        builder.addStyle(
            androidx.compose.ui.text.SpanStyle(
                background = color.copy(alpha = 0.18f),
                color = color,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            ),
            index, index + key.length,
        )
        index = lower.indexOf(key, index + key.length)
    }
    return builder.toAnnotatedString()
}
