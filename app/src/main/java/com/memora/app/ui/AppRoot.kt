package com.memora.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.memora.app.core.Prefs
import com.memora.app.core.UpdateCheck
import com.memora.app.core.UpdateInfo
import com.memora.app.core.Updater
import com.memora.app.core.task.AppForeground
import com.memora.app.core.task.SyncService
import com.memora.app.core.task.TaskEngine
import com.memora.app.ui.screens.AboutScreen
import com.memora.app.ui.screens.ArchiveCheckScreen
import com.memora.app.ui.screens.ArchiveScreen
import com.memora.app.ui.screens.FriendDetailScreen
import com.memora.app.ui.screens.HomeScreen
import com.memora.app.ui.screens.ImportArchiveScreen
import com.memora.app.ui.screens.LicensesScreen
import com.memora.app.ui.screens.LoginScreen
import com.memora.app.ui.screens.MediaCenterScreen
import com.memora.app.ui.screens.MomentDetailScreen
import com.memora.app.ui.screens.PeopleScreen
import com.memora.app.ui.screens.RecoveryScreen
import com.memora.app.ui.screens.SearchScreen
import com.memora.app.ui.screens.SettingsAccountScreen
import com.memora.app.ui.screens.SettingsAppearanceScreen
import com.memora.app.ui.screens.SettingsCaptureScreen
import com.memora.app.ui.screens.SettingsDiagnosticScreen
import com.memora.app.ui.screens.SettingsExportScreen
import com.memora.app.ui.screens.SettingsHomeScreen
import com.memora.app.ui.screens.SettingsStorageScreen
import com.memora.app.ui.screens.SettingsTaskNetworkScreen
import com.memora.app.ui.screens.TaskDetailScreen
import com.memora.app.ui.screens.TasksScreen
import com.memora.app.ui.screens.UpdateDialog
import com.memora.app.ui.components.LiquidBar
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.memora.app.ui.theme.LocalMemora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.nav.core.NavController
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.rememberNavController
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class TabItem(val icon: ImageVector, val label: String)

private val TABS = listOf(
    TabItem(MiuixIcons.Home, "首页"),
    TabItem(MiuixIcons.Contacts, "好友"),
    TabItem(MiuixIcons.Scan, "归档"),
    TabItem(MiuixIcons.Settings, "设置"),
)

@Composable
private fun PageSurface(content: @Composable () -> Unit) {
    val premium by Prefs.premiumMaterial.collectAsState()
    val c = LocalMemora.current
    Box(
        Modifier.fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        if (premium) {
            Canvas(
                Modifier.fillMaxSize().then(
                    if (Build.VERSION.SDK_INT >= 31) Modifier.blur(70.dp) else Modifier
                )
            ) {
                fun glow(color: Color, cx: Float, cy: Float, r: Float, alpha: Float) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(color.copy(alpha = alpha), Color.Transparent),
                            center = Offset(size.width * cx, size.height * cy),
                            radius = size.width * r,
                        ),
                        radius = size.width * r,
                        center = Offset(size.width * cx, size.height * cy),
                    )
                }
                glow(c.green, 0.12f, 0.06f, 0.75f, 0.18f)
                glow(c.blue, 0.92f, 0.42f, 0.62f, 0.12f)
                glow(c.amber, 0.2f, 0.94f, 0.6f, 0.10f)
            }
        }
        content()
    }
}

/**
 * 四个入口：首页 / 好友 / 归档 / 设置。
 * 窄屏底部导航，宽屏左侧竖栏；只渲染当前栈，避免两层页面叠加。
 */
@Composable
fun MemoraRoot() {
    val c = LocalMemora.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snackMessage by remember { mutableStateOf<String?>(null) }
    val reduceMotion by Prefs.reduceMotion.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val toast by AppState.toast.collectAsState()
    val running by TaskEngine.runningIds.collectAsState()
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    val homeNav = rememberNavController<Route>(Route.Home)
    val peopleNav = rememberNavController<Route>(Route.People)
    val recoveryNav = rememberNavController<Route>(Route.Recovery)
    val settingsNav = rememberNavController<Route>(Route.Settings)

    val activeNav: NavController = when (tab) {
        1 -> peopleNav
        2 -> recoveryNav
        3 -> settingsNav
        else -> homeNav
    }
    val atRoot = activeNav.backStack.size == 1
    val wide = LocalConfiguration.current.screenWidthDp >= 840

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        AppState.bind(context)
        withContext(Dispatchers.IO) { runCatching { TaskEngine.recoverInterrupted(context) } }
        runCatching { AppState.reload(context) }
        if (Prefs.autoCheckUpdate.value && !com.memora.app.core.Crash.inSafeMode(context)) {
            // 启动先查一次，之后每 15 分钟在前台复查，避免长时间挂着收不到更新
            while (true) {
                val r = runCatching { withContext(Dispatchers.IO) { Updater.check(context) } }.getOrNull()
                if (r is UpdateCheck.Available && r.info.versionCode != Prefs.ignoredVersion.value) {
                    update = r.info
                }
                kotlinx.coroutines.delay(15 * 60 * 1000L)
            }
        }
    }

    LaunchedEffect(toast) {
        if (toast.isNotBlank()) {
            snackMessage = toast
            AppState.consumeToast()
            kotlinx.coroutines.delay(2200)
            snackMessage = null
        }
    }

    LaunchedEffect(running) { runCatching { AppState.reload(context) } }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    AppForeground.visible = true
                    scope.launch {
                        runCatching { AppState.reload(context) }
                        running.firstOrNull()?.let { SyncService.ensure(context, it, "档案归档") }
                    }
                }
                Lifecycle.Event.ON_STOP -> AppForeground.visible = false
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = tab != 0 && atRoot) { tab = 0 }

    Box(Modifier.fillMaxSize()) {
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                SideRail(current = tab, onSelect = { tab = it })
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .zIndex(1f)
                        .clipToBounds()
                        .background(MiuixTheme.colorScheme.surface)
                ) {
                    androidx.compose.runtime.key(tab) {
                        NavHost(activeNav, reduceMotion, wide, onOpenSettings = { tab = 3 })
                    }
                }
            }
        } else {
            val glassOn by com.memora.app.core.Prefs.liquidGlass.collectAsState()
            val glassBackdrop = rememberLayerBackdrop()
            Box(Modifier.fillMaxSize()) {
                // 内容层完整铺满并记录进 backdrop，底栏才能采到真实内容
                Box(Modifier.fillMaxSize().layerBackdrop(glassBackdrop)) {
                    androidx.compose.runtime.key(tab) {
                        NavHost(activeNav, reduceMotion, wide, onOpenSettings = { tab = 3 })
                    }
                }
                if (glassOn) {
                    LiquidBar(
                        items = TABS.map { it.label to it.icon },
                        current = tab,
                        onSelect = { tab = it },
                        backdrop = glassBackdrop,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                } else {
                    Box(Modifier.align(Alignment.BottomCenter)) { BottomBar(tab) { tab = it } }
                }
            }
        }

        UpdateDialogHost(update) { update = null }

        // 只占自身大小的提示条，不铺满屏幕、不拦截点击
        snackMessage?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (wide) 24.dp else 92.dp)
                    .padding(horizontal = 24.dp),
            ) {
                Box(
                    Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .background(c.ink.copy(alpha = 0.92f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(msg, fontSize = 12.5.sp, color = c.card, maxLines = 2)
                }
            }
        }
    }
}

/** 同一栈里不允许出现两个相同路由：Miuix 会抛 Duplicate contentKey。 */
private fun NavController.pushSafely(route: Route) {
    if (backStack.any { it == route }) return
    push(route)
}

@Composable
private fun NavHost(nav: NavController, reduceMotion: Boolean, wide: Boolean, onOpenSettings: () -> Unit) {
    val pushUnique: (Route) -> Unit = { route -> nav.pushSafely(route) }
    androidx.compose.runtime.key(nav) {
    val transition = remember(reduceMotion) {
        if (reduceMotion) NavTransitions.None else NavTransitions.MiuixDefault
    }
    NavDisplay(
        navController = nav,
        modifier = Modifier.fillMaxSize().clipToBounds(),
        transition = transition,
    ) {
        entry<Route.Home> {
            PageSurface { HomeScreen(onOpen = { pushUnique(it) }, onSettings = onOpenSettings) }
        }
        entry<Route.People> { PageSurface { PeopleScreen(onOpen = { pushUnique(it) }) } }
        entry<Route.Recovery> { PageSurface { RecoveryScreen(onOpen = { pushUnique(it) }) } }
        entry<Route.Settings> { PageSurface { SettingsHomeScreen(onOpen = { pushUnique(it) }) } }
        entry<Route.Archive> { PageSurface { ArchiveScreen(onOpen = { pushUnique(it) }) } }
        entry<Route.Media> { PageSurface { MediaCenterScreen(onBack = { nav.pop() }) } }
        entry<Route.Search> { PageSurface { SearchScreen(onOpen = { pushUnique(it) }, onBack = { nav.pop() }) } }
        entry<Route.Tasks> { PageSurface { TasksScreen(onOpen = { pushUnique(it) }, onBack = { nav.pop() }) } }
        entry<Route.TaskDetail> { r -> PageSurface { TaskDetailScreen(r.taskId, onBack = { nav.pop() }) } }
        entry<Route.FriendDetail> { r -> PageSurface { FriendDetailScreen(r.uin, onBack = { nav.pop() }) } }
        entry<Route.MomentDetail> { r -> PageSurface { MomentDetailScreen(r.tid, onBack = { nav.pop() }) } }
        entry<Route.ImportArchive> { PageSurface { ImportArchiveScreen(onBack = { nav.pop() }) } }
        entry<Route.Login> { PageSurface { LoginScreen(onBack = { nav.pop() }, onDone = { nav.pop() }) } }
        entry<Route.SettingsAccount> { PageSurface { SettingsAccountScreen(onBack = { nav.pop() }, onOpen = { pushUnique(it) }) } }
        entry<Route.SettingsRecovery> { PageSurface { SettingsCaptureScreen(onBack = { nav.pop() }) } }
        entry<Route.SettingsTasks> { PageSurface { SettingsTaskNetworkScreen(onBack = { nav.pop() }, onOpen = { pushUnique(it) }) } }
        entry<Route.SettingsStorage> { PageSurface { SettingsStorageScreen(onBack = { nav.pop() }, onOpen = { pushUnique(it) }) } }
        entry<Route.SettingsAppearance> { PageSurface { SettingsAppearanceScreen(onBack = { nav.pop() }) } }
        entry<Route.SettingsExport> { PageSurface { SettingsExportScreen(onBack = { nav.pop() }, onOpen = { pushUnique(it) }) } }
        entry<Route.SettingsDiagnostic> { PageSurface { SettingsDiagnosticScreen(onBack = { nav.pop() }, onOpen = { pushUnique(it) }) } }
        entry<Route.About> { PageSurface { AboutScreen(onBack = { nav.pop() }, onOpen = { pushUnique(it) }) } }
        entry<Route.Licenses> { PageSurface { LicensesScreen(onBack = { nav.pop() }) } }
        entry<Route.ArchiveCheck> { PageSurface { ArchiveCheckScreen(onBack = { nav.pop() }, onOpen = { pushUnique(it) }) } }
    }
    }
}

/** 首页右上角的设置按钮：切到设置栈。 */
@Composable
private fun UpdateDialogHost(info: UpdateInfo?, onDismiss: () -> Unit) {
    if (info != null) UpdateDialog(info, onDismiss)
}

/** 底部导航：选中为黑色，未选中灰色，不使用绿色。 */
@Composable
private fun BottomBar(current: Int, onSelect: (Int) -> Unit) {
    val c = LocalMemora.current
    Row(
        Modifier.fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(top = 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TABS.forEachIndexed { index, item ->
            val selected = current == index
            Column(
                Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    item.icon, item.label, Modifier.size(22.dp),
                    tint = if (selected) c.ink else c.secondary.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    item.label, fontSize = 10.5.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) c.ink else c.secondary,
                )
            }
        }
    }
}

/** 平板横屏：左侧竖向导航（黑色选中）。 */
@Composable
private fun SideRail(current: Int, onSelect: (Int) -> Unit) {
    val c = LocalMemora.current
    Column(
        Modifier
            .width(88.dp)
            .fillMaxHeight()
            .background(MiuixTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(top = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TABS.forEachIndexed { index, item ->
            val selected = current == index
            Column(
                Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .clickable { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    item.icon, item.label, Modifier.size(23.dp),
                    tint = if (selected) c.ink else c.secondary.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    item.label, fontSize = 10.5.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) c.ink else c.secondary,
                )
            }
        }
    }
}
