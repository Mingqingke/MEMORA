package com.memora.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.memora.app.ui.theme.LocalMemora
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 液态玻璃底栏，玻璃效果由 Kyant Backdrop（Apache-2.0）提供：
 * 背景图层通过 Modifier.layerBackdrop 记录，这里用 drawBackdrop 采样并加
 * vibrancy / blur / lens（折射 + 色散）。API 31 以下库内自动降级为纯色。
 */
@Composable
fun LiquidBar(
    items: List<Pair<String, androidx.compose.ui.graphics.vector.ImageVector>>,
    current: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    val c = LocalMemora.current
    val haptic = LocalHapticFeedback.current
    val anim = remember { Animatable(current.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(current.toFloat()) }
    var stretch by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(current) {
        if (!dragging) anim.animateTo(current.toFloat(), spring(dampingRatio = 0.72f, stiffness = 320f))
    }
    val pos = if (dragging) dragValue else anim.value

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        val cellW = maxWidth / items.size
        val cellPx = cellW.value
        val barHeight = 64.dp
        val thumbW = cellW * 0.82f

        Box(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(barHeight / 2) },
                            effects = {
                                vibrancy()
                                blur(18.dp.toPx())
                                lens(18.dp.toPx(), 18.dp.toPx(), depthEffect = true, chromaticAberration = true)
                            },
                            highlight = { Highlight(width = 1.dp, alpha = 0.9f) },
                            shadow = { Shadow(radius = 20.dp, alpha = 0.26f) },
                            innerShadow = { InnerShadow(radius = 10.dp, alpha = 0.14f) },
                            onDrawSurface = {
                                drawRect(Color(0xFF101012).copy(alpha = if (c.dark) 0.10f else 0.06f))
                            },
                        )
                    } else {
                        Modifier.background(
                            c.card.copy(alpha = 0.85f),
                            RoundedCornerShape(barHeight / 2),
                        )
                    }
                )
                .pointerInput(items.size, cellPx) {
                    val cell = cellW.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        val startValue = anim.value
                        var moved = false
                        var lastX = startX
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (moved) {
                                    val target = dragValue.roundToInt().coerceIn(0, items.size - 1)
                                    val from = dragValue
                                    dragging = false
                                    stretch = 0f
                                    scope.launch {
                                        anim.snapTo(from)
                                        anim.animateTo(target.toFloat(), spring(dampingRatio = 0.62f, stiffness = 380f))
                                    }
                                    if (target != current) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onSelect(target)
                                    }
                                }
                                break
                            }
                            val dx = change.position.x - startX
                            if (!moved && abs(dx) > viewConfiguration.touchSlop) {
                                moved = true
                                dragging = true
                                dragValue = startValue
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            if (moved) {
                                dragValue = (dragValue + (change.position.x - lastX) / cell)
                                    .coerceIn(0f, (items.size - 1).toFloat())
                                stretch = (stretch + abs(change.position.x - lastX) * 0.00035f).coerceAtMost(0.30f)
                                lastX = change.position.x
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            // 选中滑块：纯 Dp 定位，避免 px/dp 混算造成偏移
            val pressScale = if (dragging) 1.06f else 1.0f
            val thumbWidth = cellW * 0.82f * (1f + stretch) * pressScale
            val slideX = (cellW * pos + (cellW - thumbWidth) / 2f)
                .coerceIn(2.dp, cellW * items.size - thumbWidth - 2.dp)
            Box(
                Modifier
                    .offset(x = slideX, y = 7.dp)
                    .width(thumbWidth)
                    .height(barHeight - 14.dp)
                    .then(
                        if (backdrop != null) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape((barHeight - 14.dp) / 2) },
                                effects = {
                                    vibrancy()
                                    blur((if (dragging) 10.dp else 8.dp).toPx())
                                    lens(
                                        (if (dragging) 30.dp else 22.dp).toPx(),
                                        (if (dragging) 30.dp else 22.dp).toPx(),
                                        depthEffect = true,
                                        chromaticAberration = true,
                                    )
                                },
                                highlight = { Highlight(width = 1.dp, alpha = 0.85f) },
                                shadow = { Shadow(radius = 10.dp, alpha = 0.20f) },
                                innerShadow = { InnerShadow(radius = 6.dp, alpha = 0.12f) },
                                onDrawSurface = {
                                    drawRect(Color.White.copy(alpha = if (c.dark) 0.10f else 0.22f))
                                },
                            )
                        } else {
                            Modifier.background(
                                Color.White.copy(alpha = if (c.dark) 0.16f else 0.42f),
                                RoundedCornerShape((barHeight - 14.dp) / 2),
                            )
                        }
                    ),
            )
            Row(Modifier.fillMaxWidth().height(barHeight), verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { i, (label, icon) ->
                    val selected = current == i
                    Column(
                        Modifier
                            .width(cellW)
                            .clickable {
                                if (i != current) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelect(i)
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = icon,
                            contentDescription = label,
                            modifier = Modifier.size(21.dp),
                            tint = if (selected) c.ink else c.secondary,
                        )
                        Spacer(Modifier.height(3.dp))
                        androidx.compose.material3.Text(
                            text = label,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = if (selected) c.ink else c.secondary,
                        )
                    }
                }
            }
        }
    }
}
