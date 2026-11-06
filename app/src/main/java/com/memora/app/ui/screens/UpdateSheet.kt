package com.memora.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import com.memora.app.ui.components.BoxCard
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memora.app.core.Prefs
import com.memora.app.core.UpdateInfo
import com.memora.app.core.Updater
import com.memora.app.ui.components.GhostButton
import com.memora.app.ui.theme.LocalMemora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 更新弹窗：显示版本与说明，下载时给真实进度，
 * 校验通过才交给系统安装；失败如实显示原因。
 */
@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(-1f) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    OverlayDialog(
        show = true,
        title = "发现新版本",
        summary = "",
        onDismissRequest = { if (!busy) onDismiss() },
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    info.versionName,
                    fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.5).sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "当前 ${com.memora.app.BuildConfig.VERSION_NAME}",
                    fontSize = 11.5.sp, color = c.secondary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    if (info.size > 0) "%.1f MB".format(info.size / 1024f / 1024f) else null,
                    if (info.sha256.isNotBlank()) "SHA-256 校验" else null,
                ).joinToString(" · "),
                fontSize = 11.sp, color = c.secondary,
            )
            Spacer(Modifier.height(14.dp))
            BoxCard(corner = 16) {
                Text("更新内容", fontSize = 11.sp, color = c.secondary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                if (info.notes.isEmpty()) {
                    Text("修复了一些已知问题", fontSize = 12.5.sp)
                } else {
                    info.notes.take(8).forEach { line ->
                        Text("· $line", fontSize = 12.5.sp, lineHeight = 21.sp)
                    }
                }
            }
            if (progress >= 0f) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = if (progress >= 1f) null else progress,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (progress >= 1f) "正在校验" else "下载中 ${(progress * 100).toInt()}%",
                    fontSize = 11.sp, color = c.secondary,
                )
            }
            if (message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(message, fontSize = 11.5.sp, color = c.danger)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(if (busy) "请稍候" else "稍后") {
                    if (!busy) {
                        Prefs.setIgnoredVersion(info.versionCode)
                        onDismiss()
                    }
                }
                GhostButton(if (busy) "处理中" else "下载并安装") {
                    if (busy) return@GhostButton
                    busy = true
                    message = ""
                    progress = 0f
                    scope.launch {
                        val file = Updater.download(context, info) { p -> progress = p }
                        if (file == null) {
                            progress = -1f
                            message = "下载或校验失败 请稍后重试"
                        } else {
                            progress = 1f
                            val ok = withContext(Dispatchers.Main) { Updater.install(context, file) }
                            if (!ok) message = "无法启动安装 请检查权限"
                            else onDismiss()
                        }
                        busy = false
                    }
                }
            }
        }
    }
}
