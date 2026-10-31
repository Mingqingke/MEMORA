package com.memora.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebResourceRequest
import com.memora.app.ui.components.Chip
import com.memora.app.core.SessionStore
import com.memora.app.core.SourceStatus
import com.memora.app.core.db.Db
import com.memora.app.core.export.Importer
import com.memora.app.core.net.QrLogin
import com.memora.app.core.net.QzoneAuth
import com.memora.app.core.task.Probe
import com.memora.app.ui.AppState
import com.memora.app.ui.components.BoxCard
import com.memora.app.ui.components.GhostButton
import com.memora.app.ui.components.PrimaryButton
import com.memora.app.ui.components.ScreenTitle
import com.memora.app.ui.components.cardColor
import com.memora.app.ui.theme.LocalMemora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.overlay.OverlayDialog

private enum class Stage { LOGIN, SESSION, DONE, FAIL }

/**
 * 登录：默认就是官方登录页面（可直接扫码或输账号），另外提供一个纯扫码模式。
 * WebView 不做裁切、不强制图层，避免部分机型只渲染出一块灰。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf("web") }
    var stage by remember { mutableStateOf(Stage.LOGIN) }
    var status by remember { mutableStateOf("在下方页面完成登录") }
    var busy by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var pasteOpen by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var reloadKey by remember { mutableStateOf(0) }
    var spaceOpened by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var uinPreview by remember { mutableStateOf("") }

    // 扫码模式
    var qr by remember { mutableStateOf<QrLogin.Session?>(null) }
    var qrTick by remember { mutableStateOf(0) }

    fun harvestCookie(): String {
        val urls = listOf("https://user.qzone.qq.com", "https://qzone.qq.com", "https://i.qq.com")
        val all = LinkedHashMap<String, String>()
        urls.forEach { u ->
            runCatching { CookieManager.getInstance().getCookie(u) }.getOrNull()?.split(";")?.forEach { kv ->
                val k = kv.substringBefore("=").trim()
                val v = kv.substringAfter("=", "").trim()
                if (k.isNotBlank() && v.isNotBlank()) all[k] = v
            }
        }
        return all.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun connect(raw: String? = null) {
        if (busy) return
        val cookie = raw ?: harvestCookie()
        val uin = SessionStore.uinFromCookie(cookie)
        if (cookie.isBlank() || uin.isBlank()) { status = "还没有检测到登录"; return }
        busy = true
        stage = Stage.SESSION
        status = "正在连接空间"
        scope.launch {
            try {
            SessionStore.save(context, cookie, uin, "")
            status = "正在连接空间"
            val boot = withContext(Dispatchers.IO) { runCatching { QzoneAuth.bootstrap(context, uin, cookie) }.getOrNull() }
            if (boot == null) {
                stage = Stage.FAIL
                status = "连接时出错 请重试"
                busy = false
                return@launch
            }
            if (boot.status == SourceStatus.AVAILABLE) {
                SessionStore.save(context, boot.session.cookie, uin, boot.session.nickname)
                if (boot.session.nickname.isNotBlank()) {
                    SessionStore.saveNickname(context, boot.session.nickname, boot.session.avatar)
                }
                status = "正在保存账号"
                runCatching { AppState.syncAccount(context) }
                    .onFailure { com.memora.app.core.Crash.record(context, it) }
                uinPreview = uin
                stage = Stage.DONE
                status = "连接成功"
                status = "正在检查来源"
                runCatching { withContext(Dispatchers.IO) { Probe.all(context, "qq:" + uin) } }
                status = "正在读取档案"
                runCatching { withContext(Dispatchers.IO) { AppState.reload(context) } }
                onDone()
            } else {
                stage = Stage.FAIL
                status = boot.detail
            }
            busy = false
            } catch (t: Throwable) {
                stage = Stage.FAIL
                status = "连接时出错 " + (t.message ?: t.javaClass.simpleName).take(60)
                busy = false
            }
        }
    }

    LaunchedEffect(mode, qrTick) {
        if (mode != "qr") return@LaunchedEffect
        status = "正在生成二维码"
        val s = withContext(Dispatchers.IO) { runCatching { QrLogin.start() }.getOrNull() }
        qr = s
        status = if (s?.image == null) "二维码没有取到 刷新一下" else "用手机 QQ 扫码"
        if (s == null) return@LaunchedEffect
        var tick = 0
        while (tick++ < 90 && stage != Stage.DONE) {
            delay(2000)
            val p = withContext(Dispatchers.IO) { runCatching { QrLogin.poll(s) }.getOrNull() } ?: break
            status = p.message
            if (p.state == QrLogin.State.CONFIRMED) {
                val cookie = withContext(Dispatchers.IO) { runCatching { QrLogin.finish(s, p.redirect) }.getOrDefault("") }
                if (cookie.isNotBlank()) connect(cookie)
                break
            }
            if (p.state == QrLogin.State.EXPIRED || p.state == QrLogin.State.BLOCKED) break
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("连接账号", if (mode == "web") "官方登录页面" else "扫码登录", onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp)) {
            BoxCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("官方页面", mode == "web") { mode = "web" }
                        Chip("扫码", mode == "qr") { mode = "qr" }
                    }
                    StepDot(if (stage == Stage.DONE) 3 else if (busy) 2 else 1, true)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    status, fontSize = 12.sp,
                    color = if (stage == Stage.FAIL) c.danger else c.secondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        if (mode == "web") {
            Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(android.graphics.Color.WHITE)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 13; MI 9) AppleWebKit/537.36 " +
                                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = object : WebChromeClient() {}
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                    if (request?.isForMainFrame == true) status = "页面加载失败 检查网络或点「其他方式」"
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (busy || stage == Stage.DONE) return
                                    val cookie = harvestCookie()
                                    val uin = SessionStore.uinFromCookie(cookie)
                                    val hasPsKey = cookie.contains("p_skey=")
                                    val hasSkey = cookie.contains("skey=")
                                    when {
                                        uin.isBlank() -> status = "在页面里扫码或登录"
                                        hasPsKey -> { status = "已登录 正在连接"; connect() }
                                        hasSkey && !spaceOpened -> {
                                            spaceOpened = true
                                            status = "正在打开空间"
                                            view?.loadUrl("https://user.qzone.qq.com/$uin")
                                        }
                                        hasSkey -> { status = "正在建立会话"; connect() }
                                        else -> Unit
                                    }
                                }
                            }
                            loadUrl("https://i.qq.com/?s_url=https%3A%2F%2Fuser.qzone.qq.com%2F")
                            web = this
                        }
                    },
                )
            }
        } else {
            Column(
                Modifier.weight(1f).padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BoxCard {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        val bmp = qr?.image
                        Box(
                            Modifier.size(200.dp).clip(RoundedCornerShape(14.dp)).background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (bmp != null) {
                                Image(bitmap = bmp.asImageBitmap(), contentDescription = "登录二维码", modifier = Modifier.size(186.dp))
                            } else {
                                Text("二维码加载中", fontSize = 12.sp, color = c.secondary)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("手机 QQ → 扫一扫", fontSize = 11.5.sp, color = c.secondary)
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mode == "web") {
                GhostButton("重新加载") { web?.reload() }
            } else {
                GhostButton("刷新二维码") { qrTick++ }
            }
            GhostButton("其他方式") { moreOpen = true }
            Spacer(Modifier.weight(1f))
            Text(if (uinPreview.isNotBlank()) uinPreview else "", fontSize = 10.5.sp, color = c.secondary)
        }
    }

    if (moreOpen) {
        OverlayDialog(show = true, title = "其他方式", summary = "官方页面也打不开时再用", onDismissRequest = { moreOpen = false }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    "在浏览器里登录 QQ 空间 回到这里粘贴 Cookie",
                    fontSize = 11.5.sp, color = c.secondary, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("打开浏览器") {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://i.qq.com/"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                    GhostButton("粘贴 Cookie") { pasteOpen = true }
                }
            }
        }
    }

    if (pasteOpen) {
        OverlayDialog(show = true, title = "粘贴 Cookie", summary = "需要包含 p_skey", onDismissRequest = { pasteOpen = false }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                TextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    label = "uin=o0123; p_skey=...",
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("取消") { pasteOpen = false }
                    GhostButton("连接") { pasteOpen = false; connect(pasted.trim()) }
                }
            }
        }
    }
}

@Composable
private fun StepDot(index: Int, active: Boolean) {
    val c = LocalMemora.current
    Box(
        Modifier.size(20.dp).clip(CircleShape).background(if (active) c.green else c.hairline),
        contentAlignment = Alignment.Center,
    ) { Text("$index", fontSize = 9.5.sp, color = if (active) Color.White else c.secondary) }
}

@Composable
fun ImportArchiveScreen(onBack: () -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by AppState.account.collectAsState()
    var preview by remember { mutableStateOf<Importer.Preview?>(null) }
    var name by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        name = uri.lastPathSegment ?: "archive"
        working = true
        scope.launch {
            preview = withContext(Dispatchers.IO) { Importer.preview(context, uri) }
            working = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("导入旧档案", "先预览 再写入", onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp)) {
            BoxCard {
                Text("支持", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("MEMORA 导出的 ZIP / JSON · 其它工具的说说 JSON", fontSize = 12.sp, color = c.secondary)
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (working) "读取中" else "选择文件", enabled = !working) {
                    picker.launch(arrayOf("application/json", "text/plain", "application/zip", "application/octet-stream"))
                }
            }
            if (result.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(result, fontSize = 12.5.sp, color = c.green)
            }
        }
    }

    preview?.let { p ->
        OverlayDialog(show = true, title = "导入预览", summary = name, onDismissRequest = { preview = null }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(p.message, fontSize = 13.sp, color = if (p.valid) c.ink else c.danger)
                Spacer(Modifier.height(6.dp))
                Text("格式 ${p.format.ifBlank { "未知" }} · 内容 ${p.moments.size} · 好友 ${p.friends.size}", fontSize = 11.5.sp, color = c.secondary)
                p.warnings.take(3).forEach { w ->
                    Spacer(Modifier.height(4.dp))
                    Text("· $w", fontSize = 11.sp, color = c.amber)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("取消") { preview = null }
                    if (p.valid && p.moments.isNotEmpty() && account != null) {
                        GhostButton("确认导入") {
                            val acc = account ?: return@GhostButton
                            scope.launch {
                                val msg = withContext(Dispatchers.IO) { Importer.apply(Db.get(context), acc.id, p) }
                                result = msg
                                preview = null
                                AppState.reload(context)
                                AppState.notify(msg)
                            }
                        }
                    }
                }
            }
        }
    }
}
