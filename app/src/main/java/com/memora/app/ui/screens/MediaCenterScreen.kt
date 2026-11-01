package com.memora.app.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.memora.app.core.db.Db
import com.memora.app.ui.AppState
import com.memora.app.ui.components.Chip
import com.memora.app.ui.components.EmptyBlock
import com.memora.app.ui.components.GhostButton
import com.memora.app.ui.components.ScreenTitle
import com.memora.app.ui.components.SectionLabel
import com.memora.app.ui.components.formatBytes
import com.memora.app.ui.components.formatDay
import com.memora.app.ui.theme.LocalMemora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import java.io.File

/**
 * 照片与视频：直接读本地文件，不联网。
 * 只展示真实存在的文件与真实失败状态，缺失的会标出来而不是隐藏。
 */
@Composable
fun MediaCenterScreen(onBack: () -> Unit) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val account by AppState.account.collectAsState()
    var filter by remember { mutableStateOf("all") }
    var rows by remember { mutableStateOf<List<Db.MediaRow>>(emptyList()) }
    var preview by remember { mutableStateOf<Db.MediaRow?>(null) }

    LaunchedEffect(account?.id, filter) {
        val acc = account ?: return@LaunchedEffect
        rows = withContext(Dispatchers.IO) {
            runCatching {
            val db = Db.get(context)
            when (filter) {
                "image" -> db.mediaRows(acc.id, 0, false, 300, 0)
                "video" -> db.mediaRows(acc.id, 1, false, 300, 0)
                "broken" -> db.mediaRows(acc.id, null, true, 300, 0)
                else -> db.mediaRows(acc.id, null, false, 300, 0)
            }
            }.getOrDefault(emptyList())
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("照片与视频", "${rows.size} 个", onBack = onBack)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            listOf(
                "all" to "全部", "image" to "图片", "video" to "视频", "broken" to "待修复",
            ).forEach { (k, v) -> Chip(v, filter == k) { filter = k } }
        }
        Spacer(Modifier.height(10.dp))
        if (rows.isEmpty()) {
            EmptyBlock("这里还没有文件", "归档完成后图片和视频会出现在这里")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.id }) { row ->
                    MediaCell(row) {
                        if (row.kind == 1) playVideo(context, row) else preview = row
                    }
                }
            }
        }
    }

    preview?.let { row ->
        OverlayDialog(
            show = true,
            title = formatDay(row.time),
            summary = listOfNotNull(
                if (row.bytes > 0) formatBytes(row.bytes) else null,
                if (row.hash.isNotBlank()) row.hash.take(8) else null,
            ).joinToString(" · "),
            onDismissRequest = { preview = null },
        ) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, row.id) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { BitmapFactory.decodeFile(row.localPath)?.asImageBitmap() }.getOrNull()
                    }
                }
                val b = bmp
                if (b != null) {
                    Image(
                        bitmap = b, contentDescription = null,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text("文件无法读取", fontSize = 12.sp, color = c.secondary)
                }
                Spacer(Modifier.height(10.dp))
                GhostButton("关闭") { preview = null }
            }
        }
    }
}

@Composable
private fun MediaCell(row: Db.MediaRow, onClick: () -> Unit) {
    val c = LocalMemora.current
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(c.hairline)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (row.kind == 0) {
            val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, row.id, row.localPath) {
                value = withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeFile(row.localPath)?.asImageBitmap() }.getOrNull()
                }
            }
            val b = bmp
            if (b != null) {
                Image(b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(stateLabel(row.state), fontSize = 10.sp, color = c.secondary)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("视频", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(stateLabel(row.state), fontSize = 9.5.sp, color = c.secondary)
            }
        }
        if (row.state != 2) {
            Box(
                Modifier.fillMaxSize().background(c.ink.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) { Text(stateLabel(row.state), fontSize = 10.sp, color = c.card) }
        }
    }
}

private fun stateLabel(state: Int) = when (state) {
    0 -> "待下载"; 1 -> "下载中"; 2 -> ""; 3 -> "失败"; 4 -> "链接失效"; else -> "未知"
}

private fun playVideo(context: android.content.Context, row: Db.MediaRow) {
    val file = File(row.localPath)
    if (!file.exists()) return
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
