package com.memora.app.ui

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/** 全应用唯一路由。四个主入口各自一条栈，设置是独立栈。 */
@Serializable
sealed interface Route : NavKey {

    // 主入口
    @Serializable data object Home : Route
    @Serializable data object People : Route
    @Serializable data object Recovery : Route
    @Serializable data object Archive : Route

    // 设置栈
    @Serializable data object Settings : Route
    @Serializable data object SettingsAccount : Route
    @Serializable data object SettingsRecovery : Route
    @Serializable data object SettingsTasks : Route
    @Serializable data object SettingsStorage : Route
    @Serializable data object SettingsAppearance : Route
    @Serializable data object SettingsExport : Route
    @Serializable data object SettingsDiagnostic : Route
    @Serializable data object About : Route
    @Serializable data object Licenses : Route

    // 功能页
    @Serializable data object Login : Route
    @Serializable data object ImportArchive : Route
    @Serializable data object Search : Route
    @Serializable data object Tasks : Route
    @Serializable data object Media : Route
    @Serializable data object ArchiveCheck : Route

    @Serializable data class FriendDetail(val uin: String) : Route
    @Serializable data class MomentDetail(val tid: String) : Route
    @Serializable data class TaskDetail(val taskId: String) : Route
}
