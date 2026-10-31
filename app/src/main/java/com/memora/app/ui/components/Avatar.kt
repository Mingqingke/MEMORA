package com.memora.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.memora.app.core.net.AvatarStore
import com.memora.app.ui.theme.LocalMemora
import top.yukonga.miuix.kmp.basic.Text

/**
 * QQ 头像。没有网络或地址失效时退回昵称首字，不留空白方块。
 */
@Composable
fun Avatar(
    uin: String,
    rawUrl: String,
    size: Dp,
    fallbackText: String = "",
    fallbackTone: Color? = null,
    square: Boolean = false,
) {
    val c = LocalMemora.current
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, uin, rawUrl) {
        value = AvatarStore.load(context, uin, rawUrl)
    }
    val shape = if (square) androidx.compose.foundation.shape.RoundedCornerShape(size / 3.2f) else CircleShape
    Box(
        Modifier.size(size).clip(shape).background((fallbackTone ?: c.accent).copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size).clip(shape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                (fallbackText.ifBlank { uin }).take(1).ifBlank { "?" },
                fontSize = (size.value * 0.4f).sp,
                fontWeight = FontWeight.SemiBold,
                color = fallbackTone ?: c.accent,
            )
        }
    }
}
