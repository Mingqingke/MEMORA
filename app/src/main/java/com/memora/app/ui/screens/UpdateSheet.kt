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
        title = "发现新版本 ${info.versionName}",
        summary = "当前 ${com.memora.app.BuildConfig.VERSION_NAME}",
        onDismissRequest = { if (!busy) onDismiss() },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            if (info.notes.isEmpty()) {
                Text("修复了一些已知问题", fontSize = 12.sp, color = c.secondary)
            } else {
                info.notes.take(6).forEach { line ->
                    Text("· $line", fontSize = 12.sp, color = c.secondary, lineHeight = 20.sp)
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
