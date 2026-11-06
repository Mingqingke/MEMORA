package com.memora.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.memora.app.core.Prefs
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 色板与 SCOPE 完全一致：中性灰页面 + 薄荷绿主卡 + 绿色状态，
 * 只用一枚蓝色做强调。MEMORA 自己的品牌色是这套中性体系，不是另起一套。
 */
@Immutable
data class MemoraColors(
    val dark: Boolean,
    val page: Color,
    val card: Color,
    val ink: Color,
    val secondary: Color,
    val hairline: Color,
    val mint: Color,
    val mintEdge: Color,
    val green: Color,
    val danger: Color,
    val dangerBg: Color,
    val dangerInk: Color,
    val blue: Color,
    val amber: Color,
) {
    // 按钮与强调一律用墨色，不用绿
    val accent: Color get() = ink
    val pastel: Color get() = mint
    val pastelInk: Color get() = ink
    val accentSoft: Color get() = if (dark) Color(0xFF26272A) else Color(0xFFEDEEF1)
    val ok: Color get() = green
    val warm: Color get() = amber
    val dangerSoft: Color get() = dangerBg
}

val MemoraLight = MemoraColors(
    dark = false,
    page = Color(0xFFF4F4F6),
    card = Color(0xFFFFFFFF),
    ink = Color(0xFF0A0A0A),
    secondary = Color(0xFF8A8F98),
    hairline = Color(0xFFECEDF0),
    mint = Color(0xFFF1F2F4),
    mintEdge = Color(0xFFE6E8EC),
    green = Color(0xFF2F9F68),
    danger = Color(0xFFC93B31),
    dangerBg = Color(0xFFF6C7C1),
    dangerInk = Color(0xFF8E2A20),
    blue = Color(0xFF3482FF),
    amber = Color(0xFFC98A1B),
)

val MemoraDark = MemoraColors(
    dark = true,
    page = Color(0xFF0E0F11),
    card = Color(0xFF1A1C1F),
    ink = Color(0xFFF2F2F2),
    secondary = Color(0xFF8E939B),
    hairline = Color(0xFF26292D),
    mint = Color(0xFF202226),
    mintEdge = Color(0xFF2B2E33),
    green = Color(0xFF4CC38A),
    danger = Color(0xFFF07A70),
    dangerBg = Color(0xFF4A211D),
    dangerInk = Color(0xFFF3B4AA),
    blue = Color(0xFF5B9BFF),
    amber = Color(0xFFE0B25C),
)

val LocalMemora = staticCompositionLocalOf { MemoraLight }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0A0A0A),
    background = Color(0xFFF4F4F6),
    onBackground = Color(0xFF0A0A0A),
    surface = Color(0xFFF4F4F6),
    onSurface = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFFFFFFFF),
    onSurfaceContainer = Color(0xFF0A0A0A),
    onSurfaceContainerVariant = Color(0xFF8A8F98),
    onSurfaceVariantSummary = Color(0x99000000),
    outline = Color(0xFFE3E4E8),
    dividerLine = Color(0xFFEDEEF1),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF2F2F2),
    background = Color(0xFF0E0F11),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0E0F11),
    onSurface = Color(0xFFF2F2F2),
    surfaceContainer = Color(0xFF1A1C1F),
    onSurfaceContainer = Color(0xFFEDEDED),
    onSurfaceContainerVariant = Color(0xFF8E939B),
    onSurfaceVariantSummary = Color(0x99FFFFFF),
    outline = Color(0xFF2A2D31),
    dividerLine = Color(0xFF26292D),
)

@Composable
fun MemoraTheme(mode: Int, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        1 -> false
        2 -> true
        else -> systemDark
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val premium by Prefs.premiumMaterial.collectAsState()
    val base = if (dark) MemoraDark else MemoraLight
    val scoped = if (premium) {
        base.copy(card = base.card.copy(alpha = 0.62f), hairline = base.hairline.copy(alpha = 0.5f))
    } else base
    CompositionLocalProvider(LocalMemora provides scoped) {
        MiuixTheme(colors = if (dark) DarkScheme else LightScheme) { content() }
    }
}
