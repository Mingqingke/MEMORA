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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memora.app.core.Friend
import com.memora.app.core.Prefs
import com.memora.app.core.db.Db
import com.memora.app.core.task.TaskEngine
import com.memora.app.ui.AppState
import com.memora.app.ui.Route
import com.memora.app.ui.components.Avatar
import com.memora.app.ui.components.Badge
import com.memora.app.ui.components.BoxCard
import com.memora.app.ui.components.Chip
import com.memora.app.ui.components.Divider
import com.memora.app.ui.components.EmptyBlock
import com.memora.app.ui.components.PrimaryButton
import com.memora.app.ui.components.ScreenTitle
import com.memora.app.ui.components.SectionLabel
import com.memora.app.ui.components.formatTime
import com.memora.app.ui.theme.LocalMemora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun PeopleScreen(onOpen: (Route) -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by AppState.account.collectAsState()
    var filter by remember { mutableStateOf("all") }
    var list by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(account?.id, filter, reload, AppState.tasks.collectAsState().value.firstOrNull()?.updatedAt) {
        val acc = account ?: return@LaunchedEffect
        list = withContext(Dispatchers.IO) {
            runCatching { Db.get(context).friends(acc.id, filter) }.getOrDefault(emptyList())
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("好友", "快照 · 变化 · 消失")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "all" to "全部", "recent" to "最近新增", "changed" to "有变化", "gone" to "消失",
            ).forEach { (key, label) ->
                Chip(label, filter == key) { filter = key }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (account == null) {
            EmptyBlock("尚未连接账号", "登录后第一次好友扫描会生成关系快照")
            return@Column
        }
        if (list.isEmpty()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                item {
                    EmptyBlock(
                        if (filter == "all") "还没有好友数据" else "这个筛选下没有记录",
                        if (filter == "all") "扫描会记录首次发现与变化" else "换个筛选条件",
                    ) {
                        PrimaryButton("归档好友") {
                            val id = AppState.startTask(context, "friend", "归档好友")
                            if (id != null) AppState.notify("正在归档好友")
                        }
                    }
                }
            }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.uin }) { f ->
                BoxCard(onClick = { onOpen(Route.FriendDetail(f.uin)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(
                            uin = f.uin, rawUrl = f.avatar, size = 40.dp,
                            fallbackText = f.displayName,
                            fallbackTone = if (f.goneState > 0) c.warm else c.accent,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.displayName, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                buildList {
                                    if (f.remark.isNotBlank()) add(f.remark)
                                    if (f.groups.isNotBlank()) add(f.groups)
                                    add(
                                        if (f.addedAt > 0) "加于 ${formatTime(f.addedAt)}"
                                        else "首次 ${formatTime(f.firstSeenAt)}"
                                    )
                                }.joinToString(" · "),
                                fontSize = 11.5.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (f.goneState > 0) {
                            Badge(if (f.goneState > 1) "已消失" else "疑似消失", c.warm)
                        } else if (f.firstSeenAt > System.currentTimeMillis() - 30L * 24 * 3600 * 1000) {
                            Badge("新增", c.ok)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FriendDetailScreen(uin: String, onBack: () -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val account by AppState.account.collectAsState()
    val scope = rememberCoroutineScope()
    var friend by remember { mutableStateOf<Friend?>(null) }
    var changes by remember { mutableStateOf<List<Triple<String, String, Long>>>(emptyList()) }
    var snaps by remember { mutableStateOf<List<Triple<Long, Int, String>>>(emptyList()) }
    var acts by remember { mutableStateOf<List<com.memora.app.core.Activity>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }
    var tags by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(account?.id, uin, reload) {
        val acc = account ?: return@LaunchedEffect
        val db = Db.get(context)
        withContext(Dispatchers.IO) {
            runCatching {
                friend = db.friend(acc.id, uin)
                changes = db.friendChanges(acc.id, uin)
                snaps = db.snapshots(acc.id)
                acts = db.activitiesByActor(acc.id, uin)
            }
        }
        friend?.let { tags = it.tags; note = it.note }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(friend?.displayName ?: "好友", uin, onBack = onBack, actions = {
            Text(
                "编辑",
                fontSize = 13.sp, color = c.secondary,
                modifier = Modifier.clickable { editing = true }.padding(8.dp),
            )
        })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                BoxCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(
                            uin = uin, rawUrl = friend?.avatar.orEmpty(), size = 48.dp,
                            fallbackText = friend?.displayName ?: "?",
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(friend?.nickname?.ifBlank { "未记录昵称" } ?: "", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                friend?.goneHint ?: "",
                                fontSize = 11.5.sp,
                                color = if ((friend?.goneState ?: 0) > 0) c.warm else c.secondary,
                            )
                        }
                        Badge(friend?.goneLabel ?: "", if ((friend?.goneState ?: 0) > 0) c.warm else c.ok)
                    }
                    Spacer(Modifier.height(10.dp))
                    Divider()
                    Spacer(Modifier.height(10.dp))
                    InfoLine("备注", friend?.remark.orEmpty())
                    InfoLine("分组", friend?.groups.orEmpty())
                    InfoLine(
                        "添加时间",
                        friend?.addedAt?.takeIf { it > 0 }?.let { formatTime(it) } ?: "列表未提供",
                    )
                    InfoLine("首次记录", formatTime(friend?.firstSeenAt))
                    InfoLine("最近出现", formatTime(friend?.lastSeenAt))
                    InfoLine("快照", "${friend?.snapshots ?: 0} 次出现 · ${friend?.misses ?: 0} 次缺失")
                    InfoLine("变化", "${changes.size} 条记录")
                }
            }
            if (tags.isNotBlank() || note.isNotBlank()) {
                item {
                    BoxCard {
                        if (tags.isNotBlank()) InfoLine("标签", tags)
                        if (note.isNotBlank()) InfoLine("私人备注", note)
                    }
                }
            }
            item {
                SectionLabel("关系变化", "${changes.size} 条")
                BoxCard {
                    if (changes.isEmpty()) {
                        Text("没有检测到昵称 备注 分组变化", fontSize = 13.sp, color = c.secondary)
                    } else {
                        changes.take(40).forEachIndexed { i, (type, diff, at) ->
                            if (i > 0) Divider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(changeLabel(type), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(2.dp))
                                    Text(diff, fontSize = 11.5.sp, color = c.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Text(formatTime(at), fontSize = 10.5.sp, color = c.secondary)
                            }
                        }
                    }
                }
            }
            if (acts.isNotEmpty()) {
                item {
                    SectionLabel("相关互动", "${acts.size} 条")
                    BoxCard {
                        acts.forEachIndexed { i, a ->
                            if (i > 0) Divider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Badge(
                                    when (a.type) {
                                        "like" -> "点赞"; "comment" -> "评论"; "reply" -> "回复"; else -> a.type
                                    },
                                    c.accent,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    a.content.ifBlank { "内容 ID ${a.targetTid}" },
                                    fontSize = 12.sp, modifier = Modifier.weight(1f),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                Text(formatTime(a.time), fontSize = 10.sp, color = c.secondary)
                            }
                        }
                    }
                }
            }
            item {
                SectionLabel("账号快照", "${snaps.size} 次")
                BoxCard {
                    if (snaps.isEmpty()) {
                        Text("还没有快照", fontSize = 13.sp, color = c.secondary)
                    } else {
                        snaps.take(12).forEachIndexed { i, (at, count, src) ->
                            if (i > 0) Divider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(formatTime(at), fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text("$count 位好友", fontSize = 12.sp, color = c.secondary)
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "消失判定需要连续两次快照都没有出现 单次缺失可能只是接口没有返回",
                    fontSize = 11.sp, color = c.secondary, lineHeight = 17.sp,
                )
            }
        }
    }

    if (editing) {
        var tagInput by remember { mutableStateOf(tags) }
        var noteInput by remember { mutableStateOf(note) }
        OverlayDialog(
            show = true,
            onDismissRequest = { editing = false },
            title = "编辑档案",
        ) {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Text("标签", fontSize = 12.sp, color = c.secondary)
                TextField(value = tagInput, onValueChange = { tagInput = it }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                Text("私人备注 只保存在本机", fontSize = 12.sp, color = c.secondary)
                TextField(value = noteInput, onValueChange = { noteInput = it }, singleLine = false)
                Spacer(Modifier.height(16.dp))
                PrimaryButton("保存") {
                    scope.launch {
                        val acc = account ?: return@launch
                        withContext(Dispatchers.IO) {
                            Db.get(context).setFriendMeta(acc.id, uin, tagInput, noteInput)
                        }
                        tags = tagInput; note = noteInput
                        editing = false
                        reload++
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val c = LocalMemora.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, fontSize = 12.5.sp, color = c.secondary, modifier = Modifier.width(96.dp))
        Text(value.ifBlank { "—" }, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
    }
}

private fun changeLabel(type: String): String = when (type) {
    "NICKNAME" -> "昵称变化"
    "REMARK" -> "备注变化"
    "GROUP" -> "分组变化"
    "MISSING" -> "本次快照未出现"
    "GONE" -> "判定为已消失"
    else -> type
}
