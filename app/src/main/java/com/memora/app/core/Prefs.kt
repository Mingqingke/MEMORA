package com.memora.app.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** 设置存储：键只增不删，升级不重置用户选择。 */
object Prefs {

    private const val FILE = "memora_prefs"
    private lateinit var sp: SharedPreferences

    /** 0 跟随系统 / 1 浅色 / 2 深色 */
    val themeMode = MutableStateFlow(0)
    /** 0 Sink / 1 Tilt / 2 关闭 */
    val cardFeedback = MutableStateFlow(1)
    val reduceMotion = MutableStateFlow(false)
    val premiumMaterial = MutableStateFlow(false)
    val haptic = MutableStateFlow(true)

    /** 扫描起始年份，0 表示尽量到底 */
    val scanStartYear = MutableStateFlow(0)
    /** 每个来源最多翻页数 */
    val maxPages = MutableStateFlow(120)
    /** 每页条数 */
    val pageSize = MutableStateFlow(20)
    /** 并发来源数（1 最稳） */
    val concurrency = MutableStateFlow(1)
    val wifiOnly = MutableStateFlow(true)
    /** 仅前台任务，不在后台续跑 */
    val foregroundOnly = MutableStateFlow(false)
    val autoResume = MutableStateFlow(true)
    /** 是否下载媒体 */
    val downloadMedia = MutableStateFlow(true)
    /** 任务完成后通知 */
    val notifyDone = MutableStateFlow(true)
    /** 每次请求之间的最小间隔（毫秒） */
    val requestGapMs = MutableStateFlow(1200)
    /** 好友快照保留数量 */
    val snapshotKeep = MutableStateFlow(24)
    /** 隐藏恢复失败的条目 */
    val hideUnavailable = MutableStateFlow(false)

    /** 归档范围 */
    val includeMoments = MutableStateFlow(true)
    val includeHistory = MutableStateFlow(true)
    val includeAlbums = MutableStateFlow(true)
    val includeFriends = MutableStateFlow(true)

    val recentSearch = MutableStateFlow<List<String>>(emptyList())

    val updateSource = MutableStateFlow("")
    val autoCheckUpdate = MutableStateFlow(true)
    val liquidGlass = MutableStateFlow(false)
    val ignoredVersion = MutableStateFlow(0)
    val lastCheckAt = MutableStateFlow(0L)
    val updateDiag = MutableStateFlow("")

    val activeAccount = MutableStateFlow("")

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        themeMode.value = sp.getInt("theme_mode", 0)
        cardFeedback.value = sp.getInt("card_feedback", 1)
        reduceMotion.value = sp.getBoolean("reduce_motion", false)
        premiumMaterial.value = sp.getBoolean("premium_material", false)
        haptic.value = sp.getBoolean("haptic", true)
        scanStartYear.value = sp.getInt("scan_start_year", 0)
        maxPages.value = sp.getInt("max_pages", 120)
        pageSize.value = sp.getInt("page_size", 20)
        concurrency.value = sp.getInt("concurrency", 1)
        wifiOnly.value = sp.getBoolean("wifi_only", true)
        foregroundOnly.value = sp.getBoolean("foreground_only", false)
        autoResume.value = sp.getBoolean("auto_resume", true)
        downloadMedia.value = sp.getBoolean("download_media", true)
        notifyDone.value = sp.getBoolean("notify_done", true)
        requestGapMs.value = sp.getInt("request_gap_ms", 1200)
        snapshotKeep.value = sp.getInt("snapshot_keep", 24)
        hideUnavailable.value = sp.getBoolean("hide_unavailable", false)
        activeAccount.value = sp.getString("active_account", "") ?: ""
        includeMoments.value = sp.getBoolean("include_moments", true)
        includeHistory.value = sp.getBoolean("include_history", true)
        includeAlbums.value = sp.getBoolean("include_albums", true)
        includeFriends.value = sp.getBoolean("include_friends", true)
        updateSource.value = sp.getString("update_source", "") ?: ""
        autoCheckUpdate.value = sp.getBoolean("auto_check_update", true)
        liquidGlass.value = sp.getBoolean("liquid_glass", false)
        ignoredVersion.value = sp.getInt("ignored_version", 0)
        lastCheckAt.value = sp.getLong("last_check_at", 0L)
        updateDiag.value = sp.getString("update_diag", "") ?: ""
        recentSearch.value = (sp.getString("recent_search", "") ?: "")
            .split("\n").filter { it.isNotBlank() }.take(8)
    }

    fun setThemeMode(v: Int) = putInt("theme_mode", v, themeMode)
    fun setCardFeedback(v: Int) = putInt("card_feedback", v, cardFeedback)
    fun setReduceMotion(v: Boolean) = putBool("reduce_motion", v, reduceMotion)
    fun setPremiumMaterial(v: Boolean) = putBool("premium_material", v, premiumMaterial)
    fun setHaptic(v: Boolean) = putBool("haptic", v, haptic)
    fun setScanStartYear(v: Int) = putInt("scan_start_year", v, scanStartYear)
    fun setMaxPages(v: Int) = putInt("max_pages", v, maxPages)
    fun setPageSize(v: Int) = putInt("page_size", v, pageSize)
    fun setConcurrency(v: Int) = putInt("concurrency", v, concurrency)
    fun setWifiOnly(v: Boolean) = putBool("wifi_only", v, wifiOnly)
    fun setForegroundOnly(v: Boolean) = putBool("foreground_only", v, foregroundOnly)
    fun setAutoResume(v: Boolean) = putBool("auto_resume", v, autoResume)
    fun setDownloadMedia(v: Boolean) = putBool("download_media", v, downloadMedia)
    fun setNotifyDone(v: Boolean) = putBool("notify_done", v, notifyDone)
    fun setRequestGapMs(v: Int) = putInt("request_gap_ms", v, requestGapMs)
    fun setSnapshotKeep(v: Int) = putInt("snapshot_keep", v, snapshotKeep)
    fun setHideUnavailable(v: Boolean) = putBool("hide_unavailable", v, hideUnavailable)

    fun setIncludeMoments(v: Boolean) = putBool("include_moments", v, includeMoments)
    fun setIncludeHistory(v: Boolean) = putBool("include_history", v, includeHistory)
    fun setIncludeAlbums(v: Boolean) = putBool("include_albums", v, includeAlbums)
    fun setIncludeFriends(v: Boolean) = putBool("include_friends", v, includeFriends)

    fun pushRecentSearch(q: String) {
        if (q.isBlank()) return
        val list = (listOf(q) + recentSearch.value.filter { it != q }).take(8)
        recentSearch.value = list
        sp.edit().putString("recent_search", list.joinToString("\n")).apply()
    }

    fun clearRecentSearch() {
        recentSearch.value = emptyList()
        sp.edit().putString("recent_search", "").apply()
    }

    fun setUpdateSource(v: String) {
        updateSource.value = v
        sp.edit().putString("update_source", v).apply()
    }

    fun setAutoCheckUpdate(v: Boolean) = putBool("auto_check_update", v, autoCheckUpdate)
    fun setLiquidGlass(v: Boolean) = putBool("liquid_glass", v, liquidGlass)
    fun setIgnoredVersion(v: Int) {
        ignoredVersion.value = v
        sp.edit().putInt("ignored_version", v).apply()
    }

    fun setUpdateDiag(v: String) = putStr("update_diag", v, updateDiag)

    fun setLastCheckAt(v: Long) {
        lastCheckAt.value = v
        sp.edit().putLong("last_check_at", v).apply()
    }

    fun setActiveAccount(v: String) {
        activeAccount.value = v
        sp.edit().putString("active_account", v).apply()
    }

    private fun putInt(key: String, v: Int, flow: MutableStateFlow<Int>) {
        flow.value = v; sp.edit().putInt(key, v).apply()
    }

    private fun putStr(key: String, v: String, flow: MutableStateFlow<String>) {
        flow.value = v
        sp.edit().putString(key, v).apply()
    }

    private fun putBool(key: String, v: Boolean, flow: MutableStateFlow<Boolean>) {
        flow.value = v; sp.edit().putBoolean(key, v).apply()
    }
}
