package com.memora.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
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
import com.memora.app.ui.theme.LocalMemora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Icon
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
 * 四个底部入口：首页 / 好友 / 归档 / 设置。
 * 档案、搜索、任务、详情等页面在各自栈内 push，返回逐级出栈。
 */
@Composable
fun MemoraRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val reduceMotion by Prefs.reduceMotion.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
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

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        AppState.bind(context)
        withContext(Dispatchers.IO) { TaskEngine.recoverInterrupted(context) }
        AppState.reload(context)
        if (Prefs.autoCheckUpdate.value) {
            val r = runCatching { withContext(Dispatchers.IO) { Updater.check(context) } }.getOrNull()
            if (r is UpdateCheck.Available && r.info.versionCode != Prefs.ignoredVersion.value) {
                update = r.info
            }
        }
    }

    LaunchedEffect(toast) {
        if (toast.isNotBlank()) {
            snackbar.showSnackbar(toast)
            AppState.consumeToast()
        }
    }

    LaunchedEffect(running) { AppState.reload(context) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    AppForeground.visible = true
                    scope.launch {
                        AppState.reload(context)
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

    val transition = if (reduceMotion) NavTransitions.None else NavTransitions.MiuixDefault
    val wide = LocalConfiguration.current.screenWidthDp >= 840

    val page: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
            update?.let { info ->
                UpdateDialog(info) { update = null }
            }
            Crossfade(
                targetState = tab,
                animationSpec = tween(if (reduceMotion) 0 else 160),
                label = "tab",
            ) { current ->
                val nav = when (current) {
                    1 -> peopleNav
                    2 -> recoveryNav
                    3 -> settingsNav
                    else -> homeNav
                }
                NavDisplay(
                    navController = nav,
                    modifier = Modifier.fillMaxSize(),
                    transition = transition,
                ) {
                    entry<Route.Home> {
                        PageSurface {
                            HomeScreen(
                                onOpen = { nav.push(it) },
                                onSettings = { tab = 3 },
                            )
                        }
                    }
                    entry<Route.People> {
                        PageSurface { PeopleScreen(onOpen = { nav.push(it) }) }
                    }
                    entry<Route.Recovery> {
                        PageSurface { RecoveryScreen(onOpen = { nav.push(it) }) }
                    }
                    entry<Route.Settings> {
                        PageSurface { SettingsHomeScreen(onOpen = { settingsNav.push(it) }) }
                    }
                    entry<Route.Archive> {
                        PageSurface { ArchiveScreen(onOpen = { nav.push(it) }) }
                    }
                    entry<Route.Media> {
                        PageSurface { MediaCenterScreen(onBack = { nav.pop() }) }
                    }
                    entry<Route.Search> {
                        PageSurface { SearchScreen(onOpen = { nav.push(it) }, onBack = { nav.pop() }) }
                    }
                    entry<Route.Tasks> {
                        PageSurface { TasksScreen(onOpen = { nav.push(it) }, onBack = { nav.pop() }) }
                    }
                    entry<Route.TaskDetail> { route ->
                        PageSurface { TaskDetailScreen(route.taskId, onBack = { nav.pop() }) }
                    }
                    entry<Route.FriendDetail> { route ->
                        PageSurface { FriendDetailScreen(route.uin, onBack = { nav.pop() }) }
                    }
                    entry<Route.MomentDetail> { route ->
                        PageSurface { MomentDetailScreen(route.tid, onBack = { nav.pop() }) }
                    }
                    entry<Route.ImportArchive> {
                        PageSurface { ImportArchiveScreen(onBack = { nav.pop() }) }
                    }
                    entry<Route.Login> {
                        PageSurface { LoginScreen(onBack = { nav.pop() }, onDone = { nav.pop() }) }
                    }
                    entry<Route.SettingsAccount> {
                        PageSurface { SettingsAccountScreen(onBack = { settingsNav.pop() }, onOpen = { settingsNav.push(it) }) }
                    }
                    entry<Route.SettingsRecovery> {
                        PageSurface { SettingsCaptureScreen(onBack = { settingsNav.pop() }) }
                    }
                    entry<Route.SettingsTasks> {
                        PageSurface { SettingsTaskNetworkScreen(onBack = { settingsNav.pop() }, onOpen = { settingsNav.push(it) }) }
                    }
                    entry<Route.SettingsStorage> {
                        PageSurface { SettingsStorageScreen(onBack = { settingsNav.pop() }, onOpen = { settingsNav.push(it) }) }
                    }
                    entry<Route.SettingsAppearance> {
                        PageSurface { SettingsAppearanceScreen(onBack = { settingsNav.pop() }) }
                    }
                    entry<Route.SettingsExport> {
                        PageSurface { SettingsExportScreen(onBack = { settingsNav.pop() }, onOpen = { settingsNav.push(it) }) }
                    }
                    entry<Route.SettingsDiagnostic> {
                        PageSurface { SettingsDiagnosticScreen(onBack = { settingsNav.pop() }, onOpen = { settingsNav.push(it) }) }
                    }
                    entry<Route.About> {
                        PageSurface { AboutScreen(onBack = { settingsNav.pop() }, onOpen = { settingsNav.push(it) }) }
                    }
                    entry<Route.Licenses> {
                        PageSurface { LicensesScreen(onBack = { settingsNav.pop() }) }
                    }
                    entry<Route.ArchiveCheck> {
                        PageSurface { ArchiveCheckScreen(onBack = { settingsNav.pop() }, onOpen = { settingsNav.push(it) }) }
                    }
                }
            }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            SideRail(current = tab, onSelect = { tab = it })
            Box(Modifier.weight(1f)) { page() }
        }
    } else {
        Scaffold(
            bottomBar = {
                if (atRoot) {
                    NavigationBar {
                        NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = MiuixIcons.Home, label = "首页")
                        NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = MiuixIcons.Contacts, label = "好友")
                        NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = MiuixIcons.Scan, label = "归档")
                        NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = MiuixIcons.Settings, label = "设置")
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) { page() }
        }
    }

    SnackbarHost(snackbar, Modifier.fillMaxSize())
}

/** 平板横屏：左侧竖向导航，右侧内容铺满。 */
@Composable
private fun SideRail(current: Int, onSelect: (Int) -> Unit) {
    val c = LocalMemora.current
    val items = listOf(
        Triple(MiuixIcons.Home, "首页", 0),
        Triple(MiuixIcons.Contacts, "好友", 1),
        Triple(MiuixIcons.Scan, "归档", 2),
        Triple(MiuixIcons.Settings, "设置", 3),
    )
    Column(
        Modifier
            .width(88.dp)
            .fillMaxHeight()
            .background(MiuixTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(top = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(22.dp),
    ) {
        items.forEach { (icon, label, index) ->
            val selected = current == index
            Column(
                Modifier
                    .clickable { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    icon, label, Modifier.size(23.dp),
                    tint = if (selected) c.green else c.secondary.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    label, fontSize = 10.5.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) c.green else c.secondary,
                )
            }
        }
    }
}
