package com.memora.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memora.app.core.Prefs
import com.memora.app.ui.theme.LocalMemora
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun cardColor(): Color {
    val premium by Prefs.premiumMaterial.collectAsState()
    val c = LocalMemora.current
    return if (premium) c.card.copy(alpha = 0.78f) else c.card
}

/** 首页大卡片允许按压形变，普通列表一律不形变。 */
@Composable
fun pressFeedback(interactive: Boolean = false): PressFeedbackType {
    if (!interactive) return PressFeedbackType.None
    val mode by Prefs.cardFeedback.collectAsState()
    val reduce by Prefs.reduceMotion.collectAsState()
    return when {
        reduce || mode == 2 -> PressFeedbackType.None
        mode == 1 -> PressFeedbackType.Tilt
        else -> PressFeedbackType.Sink
    }
}

@Composable
fun ScreenTitle(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalMemora.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) 6.dp else 20.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MiuixIcons.ChevronBackward, "返回", Modifier.size(21.dp), tint = MiuixTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = if (onBack != null) 20.sp else 30.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    val c = LocalMemora.current
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.secondary, modifier = Modifier.weight(1f))
        if (!trailing.isNullOrBlank()) Text(trailing, fontSize = 11.sp, color = c.secondary.copy(alpha = 0.7f))
    }
}

@Composable
fun BoxCard(
    modifier: Modifier = Modifier,
    corner: Int = 22,
    onClick: (() -> Unit)? = null,
    interactive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = Modifier.fillMaxWidth()
    val m = if (onClick != null) base.clickable(onClick = onClick) else base
    Card(
        modifier = if (onClick != null) m else modifier.then(base),
        cornerRadius = corner.dp,
        colors = CardDefaults.defaultColors(color = cardColor()),
        pressFeedbackType = pressFeedback(interactive),
        showIndication = onClick != null,
        onClick = onClick ?: {},
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), content = content)
    }
}

@Composable
fun EntryRow(
    icon: ImageVector?,
    title: String,
    summary: String,
    onClick: () -> Unit,
    trailing: String? = null,
    tint: Color? = null,
) {
    val c = LocalMemora.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                    .background((tint ?: c.accent).copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(17.dp), tint = tint ?: c.accent) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(summary, fontSize = 12.sp, color = c.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Text(trailing, fontSize = 12.sp, color = c.secondary)
            Spacer(Modifier.width(6.dp))
        }
        Icon(MiuixIcons.ChevronForward, null, Modifier.size(14.dp), tint = c.secondary.copy(alpha = 0.5f))
    }
}

@Composable
fun Divider() {
    HorizontalDivider(
        Modifier.padding(start = 18.dp, end = 18.dp),
        color = LocalMemora.current.hairline,
    )
}

@Composable
fun StatTile(label: String, value: String, hint: String = "", modifier: Modifier = Modifier, accent: Color? = null) {
    val c = LocalMemora.current
    Card(
        modifier = modifier,
        cornerRadius = 18.dp,
        colors = CardDefaults.defaultColors(color = cardColor()),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = accent ?: c.ink, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(label, fontSize = 12.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hint.isNotBlank()) {
                Text(hint, fontSize = 10.sp, color = c.secondary.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalMemora.current
    Box(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) c.ink else c.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) (if (c.dark) Color(0xFF101113) else Color.White) else c.secondary,
        )
    }
}

@Composable
fun Badge(text: String, tone: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tone.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = 10.sp, color = tone, fontWeight = FontWeight.Medium)
    }
}

/** 完整度四格：不用百分比，避免把「线索」显示成 25%。 */
@Composable
fun Dots(level: Int, tone: Color) {
    val c = LocalMemora.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(4) { i ->
            Box(
                Modifier
                    .padding(end = 2.dp)
                    .size(width = 9.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i < level) tone else c.hairline)
            )
        }
    }
}

@Composable
fun KeyValue(label: String, value: String, mono: Boolean = false) {
    val c = LocalMemora.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 13.sp, color = c.secondary, modifier = Modifier.width(84.dp))
        Text(
            value.ifBlank { "—" },
            fontSize = 13.sp,
            fontWeight = if (mono) FontWeight.Normal else FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun EmptyBlock(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    val c = LocalMemora.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(c.accentSoft),
            contentAlignment = Alignment.Center,
        ) { Icon(MiuixIcons.Search, null, Modifier.size(20.dp), tint = c.accent) }
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            fontSize = 12.sp,
            color = c.secondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 18.sp,
        )
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

/** 来源覆盖率圆环：只统计真实探测过的来源，不显示假百分比。 */
@Composable
fun CoverageRing(
    ok: Int,
    limited: Int,
    unknown: Int,
    size: Int = 92,
    stroke: Int = 8,
    centerTop: String,
    centerBottom: String,
) {
    val c = LocalMemora.current
    val total = (ok + limited + unknown).coerceAtLeast(1)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size.dp)) {
                val sw = stroke.dp.toPx()
                val arcSize = Size(this.size.width - sw, this.size.height - sw)
                drawArc(
                    color = c.hairline, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = Offset(sw / 2, sw / 2), size = arcSize,
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
                var start = -90f
                fun seg(count: Int, color: Color) {
                    if (count <= 0) return
                    val sweep = 360f * count / total
                    drawArc(
                        color = color, startAngle = start + 2f, sweepAngle = (sweep - 4f).coerceAtLeast(1f),
                        useCenter = false, topLeft = Offset(sw / 2, sw / 2), size = arcSize,
                        style = Stroke(width = sw, cap = StrokeCap.Round),
                    )
                    start += sweep
                }
                seg(ok, c.ok); seg(limited, c.warm); seg(unknown, c.secondary.copy(alpha = 0.35f))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerTop, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(centerBottom, fontSize = 10.sp, color = c.secondary)
            }
        }
    }
}

/** 年度密度条：直接对应数据库里的真实条数。 */
@Composable
fun YearBars(years: List<Pair<Int, Int>>, selected: Int?, onSelect: (Int?) -> Unit) {
    val c = LocalMemora.current
    if (years.isEmpty()) return
    val max = years.maxOf { it.second }.coerceAtLeast(1)
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(years.takeLast(14)) { (year, count) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(30.dp)) {
                Box(
                    Modifier
                        .width(26.dp)
                        .height((14 + 40f * count / max).dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (selected == year) c.accent else c.mint)
                        .clickable { onSelect(if (selected == year) null else year) },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${year % 100}",
                    fontSize = 9.sp,
                    color = if (selected == year) c.accent else c.secondary,
                )
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalMemora.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 14.dp,
        colors = CardDefaults.defaultColors(color = if (enabled) c.ink else c.hairline),
        showIndication = true,
        onClick = { if (enabled) onClick() },
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = if (enabled) (if (c.dark) Color(0xFF101113) else Color.White) else c.secondary,
            )
        }
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit) {
    val c = LocalMemora.current
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.accentSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text, fontSize = 13.sp, color = c.accent, fontWeight = FontWeight.Medium) }
}

fun formatTime(t: Long?): String {
    if (t == null || t <= 0) return "时间未知"
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(t))
}

fun formatDay(t: Long?): String {
    if (t == null || t <= 0) return "未知"
    return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(t))
}

fun formatBytes(b: Long): String = when {
    b <= 0 -> "0 B"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024)
    else -> "%.2f GB".format(b / 1024.0 / 1024 / 1024)
}

@Composable
fun ToggleRow(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    top.yukonga.miuix.kmp.preference.SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
fun ArrowRow(title: String, summary: String, onClick: () -> Unit) {
    top.yukonga.miuix.kmp.preference.ArrowPreference(
        title = title,
        summary = summary,
        onClick = onClick,
    )
}

@Composable
fun DropRow(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    summary: String = "",
    onSelected: (Int) -> Unit,
) {
    top.yukonga.miuix.kmp.preference.OverlayDropdownPreference(
        items = items,
        selectedIndex = selectedIndex.coerceIn(0, items.size - 1),
        title = title,
        onSelectedIndexChange = onSelected,
    )
}

enum class AuthVisual { OK, WARN, OFF }

/**
 * 首页大卡右下角的圆环 + 对勾/感叹号，参数与 SCOPE 完全一致，
 * 允许被卡片边缘自然裁切。
 */
@Composable
fun CheckBadge(kind: AuthVisual, modifier: Modifier = Modifier) {
    val c = LocalMemora.current
    val color = when (kind) {
        AuthVisual.OK -> c.green
        AuthVisual.WARN -> c.danger
        AuthVisual.OFF -> c.danger.copy(alpha = 0.55f)
    }
    Canvas(modifier) {
        val d = size.minDimension
        val stroke = d * 0.075f
        val radius = d / 2f - stroke / 2f
        drawCircle(
            color = color,
            radius = radius,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round,
            ),
        )
        when (kind) {
            AuthVisual.OK -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(d * 0.30f, d * 0.52f)
                    lineTo(d * 0.44f, d * 0.66f)
                    lineTo(d * 0.72f, d * 0.36f)
                }
                drawPath(
                    path = path,
                    color = color,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = stroke * 1.1f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
                )
            }
            else -> {
                drawLine(
                    color = color,
                    start = Offset(d * 0.5f, d * 0.31f),
                    end = Offset(d * 0.5f, d * 0.56f),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawCircle(color = color, radius = stroke * 0.72f, center = Offset(d * 0.5f, d * 0.71f))
            }
        }
    }
}

/** 首页三张大卡：默认 Tilt，按压时整卡轻微倾斜（与 SCOPE 一致）。 */
@Composable
fun homePressFeedback(): PressFeedbackType {
    val mode by Prefs.cardFeedback.collectAsState()
    val reduce by Prefs.reduceMotion.collectAsState()
    return when {
        reduce || mode == 2 -> PressFeedbackType.None
        mode == 1 -> PressFeedbackType.Tilt
        else -> PressFeedbackType.Sink
    }
}

/** SCOPE 版入口卡：标题 + 说明 + 箭头。 */
@Composable
fun EntryCard(
    title: String,
    subtitle: String,
    trailing: String? = null,
    interactive: Boolean = false,
    onClick: () -> Unit,
) {
    val c = LocalMemora.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        colors = CardDefaults.defaultColors(color = cardColor()),
        pressFeedbackType = pressFeedback(interactive),
        showIndication = interactive,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, fontSize = 13.sp, color = c.secondary)
            }
            if (trailing != null) {
                Text(trailing, fontSize = 13.sp, color = c.secondary)
                Spacer(Modifier.width(6.dp))
            }
            Icon(MiuixIcons.ChevronForward, null, Modifier.size(16.dp), tint = c.secondary.copy(alpha = 0.55f))
        }
    }
}

/* ---------------- SCOPE 同款结构组件 ---------------- */

/** 首页右侧小卡：标题 13 / 数值 21 Bold / 补充 11，与 SCOPE 一致。 */
@Composable
fun MiniCard(
    title: String,
    value: String,
    sub: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = LocalMemora.current
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        colors = CardDefaults.defaultColors(color = cardColor()),
        pressFeedbackType = pressFeedback(true),
        showIndication = true,
        onClick = onClick,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.secondary, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(sub, fontSize = 11.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 首页入口卡：22 圆角 / 17 标题 / 13 副标题 / 右侧箭头，与 SCOPE 一致。 */

/**
 * 首页大卡右下角：圆环 + 环内倾斜的 QQ 企鹅标识。
 * 企鹅用的是官方 favicon 里提取的真实图，按圆环颜色着色，允许被卡片边缘裁切。
 */
@Composable
fun BigRing(
    fraction: Float?,
    tone: Color,
    modifier: Modifier = Modifier,
    showCheck: Boolean = false,
    showPenguin: Boolean = true,
) {
    val penguin = rememberDrawableBitmap(com.memora.app.R.drawable.qq_penguin)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val d = size.minDimension
            val stroke = d * 0.075f
            val radius = d / 2f - stroke / 2f
            drawCircle(
                color = tone.copy(alpha = 0.16f),
                radius = radius,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val sweep = when {
                fraction == null -> 96f
                else -> (360f * fraction).coerceAtLeast(14f)
            }
            drawArc(
                color = tone,
                startAngle = if (fraction == null) -30f else -90f,
                sweepAngle = sweep.coerceAtMost(359f),
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (showCheck) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(d * 0.30f, d * 0.52f)
                    lineTo(d * 0.44f, d * 0.66f)
                    lineTo(d * 0.72f, d * 0.36f)
                }
                drawPath(
                    path, tone,
                    style = Stroke(
                        width = stroke * 1.1f, cap = StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
                )
            }
            if (showPenguin && penguin != null) {
                // 位图在 Canvas 上按比例绘制并着色
                val side = d * 0.56f
                val src = androidx.compose.ui.geometry.Size(penguin.width.toFloat(), penguin.height.toFloat())
                val dst = androidx.compose.ui.geometry.Rect(
                    offset = Offset((size.width - side) / 2f, (size.height - side) / 2f),
                    size = androidx.compose.ui.geometry.Size(side, side),
                )
                rotate(degrees = -14f, pivot = Offset(size.width / 2f, size.height / 2f)) {
                    drawImage(
                        image = penguin,
                        srcSize = androidx.compose.ui.unit.IntSize(penguin.width, penguin.height),
                        dstOffset = androidx.compose.ui.unit.IntOffset(dst.left.toInt(), dst.top.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(side.toInt(), side.toInt()),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tone),
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    )
                }
            }
        }
    }
}


/** 资源图片统一走 BitmapFactory 解码，失败返回 null，不让页面因为一张图崩掉。 */
@Composable
fun rememberDrawableBitmap(id: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    val state = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null, id,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                android.graphics.BitmapFactory.decodeResource(context.resources, id)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return state.value
}
