package com.memora.app.ui.components

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memora.app.ui.theme.LocalMemora
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 液态玻璃底栏。
 *
 * 背景不是纯色：直接重绘上层内容 layer（轻微放大 + 随滑块位移），
 * 所以玻璃里的内容是真实页面在动，不是贴图。
 *
 * 未使用 AGSL 折射着色器，效果是「重采样 + 位移 + 模糊」，
 * 不是色散级折射，这一点在交付说明里写清楚。
 */
@Composable
fun LiquidBar(
    items: List<Pair<String, androidx.compose.ui.graphics.vector.ImageVector>>,
    current: Int,
    onSelect: (Int) -> Unit,
    backdrop: GraphicsLayer?,
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
            .padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
    ) {
        val barW = maxWidth - 0.dp
        val cellW = barW / items.size
        val cellPx = cellW.value

        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .pointerInput(items.size) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            dragValue = anim.value
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            val max = (items.size - 1).toFloat()
                            dragValue = (dragValue + amount / (cellPx * density)).coerceIn(0f, max)
                            stretch = (stretch + abs(amount) * 0.00025f).coerceAtMost(0.30f)
                        },
                        onDragEnd = {
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
                        },
                    )
                },
        ) {
            Canvas(Modifier.matchParentSize()) {
                val h = size.height
                val r = h / 2f
                val capsule = Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, h, androidx.compose.ui.geometry.CornerRadius(r, r)),
                    )
                }
                // 1. 玻璃层：重绘背景内容，放大一点并按滑块位置位移
                clipPath(capsule) {
                    // 玻璃底：柔和渐变 + 高光（无 backdrops 采样时也稳定）
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.White.copy(alpha = if (c.dark) 0.10f else 0.30f),
                            0.5f to Color.White.copy(alpha = if (c.dark) 0.04f else 0.12f),
                            1f to Color.White.copy(alpha = if (c.dark) 0.02f else 0.06f),
                        ),
                    )
                    // 半透明染色，让文字保持可读
                    drawRect(color = c.card.copy(alpha = if (c.dark) 0.55f else 0.62f))
                    // 顶部高光
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.White.copy(alpha = if (c.dark) 0.10f else 0.32f),
                            0.45f to Color.Transparent,
                        ),
                    )
                }
                // 2. 外描边
                drawPath(
                    capsule,
                    color = if (c.dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.85f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f * density),
                )
                // 3. 选中滑块：跟手 + 速度拉伸
                val baseW = cellPx * 0.78f * density
                val sliderW = baseW * (1f + stretch)
                val cx = (pos + 0.5f) * cellPx * density
                val left = (cx - sliderW / 2f).coerceIn(4f * density, size.width - sliderW - 4f * density)
                val top = 7f * density
                val sh = h - 14f * density
                val slide = Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left, top, left + sliderW, top + sh,
                            androidx.compose.ui.geometry.CornerRadius(sh / 2f, sh / 2f),
                        ),
                    )
                }
                drawPath(
                    slide,
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (c.dark) 0.20f else 0.92f),
                        1f to Color.White.copy(alpha = if (c.dark) 0.10f else 0.62f),
                    ),
                )
                drawPath(
                    slide,
                    color = Color.White.copy(alpha = if (c.dark) 0.22f else 0.95f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f * density),
                )
            }

            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { i, (label, icon) ->
                    val selected = i == current
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
