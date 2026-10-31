package com.memora.app.ui

import android.content.Context
import com.memora.app.core.Account
import com.memora.app.core.ArchiveStats
import com.memora.app.core.Prefs
import com.memora.app.core.SessionStore
import com.memora.app.core.SourceState
import com.memora.app.core.SyncTask
import com.memora.app.core.TaskStatus
import com.memora.app.core.db.Db
import com.memora.app.core.task.TaskEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** 界面共享状态：全部来自数据库与任务引擎，不保存任何演示数据。 */
object AppState {

    val account = MutableStateFlow<Account?>(null)
    val stats = MutableStateFlow(ArchiveStats())
    val sources = MutableStateFlow<List<SourceState>>(emptyList())
    val tasks = MutableStateFlow<List<SyncTask>>(emptyList())
    val friends = MutableStateFlow(0)
    val loading = MutableStateFlow(false)
    val toast = MutableStateFlow("")
    val running = TaskEngine.runningIds

    private var ctx: Context? = null

    fun bind(context: Context) {
        ctx = context.applicationContext
        val saved = Prefs.activeAccount.value
        if (saved.isNotBlank()) {
            runCatching { Db.get(context.applicationContext).account(saved) }
                .getOrNull()
                ?.let { account.value = it }
        }
    }

    suspend fun reload(context: Context) {
        val app = context.applicationContext
        loading.value = true
        try {
            val db = Db.get(app)
            val acc = account.value ?: Prefs.activeAccount.value.takeIf { it.isNotBlank() }?.let { db.account(it) }
            account.value = acc
            if (acc != null) {
                stats.value = withContext(Dispatchers.IO) { runCatching { db.stats(acc.id) }.getOrDefault(ArchiveStats()) }
                sources.value = withContext(Dispatchers.IO) { runCatching { db.sourceStates(acc.id) }.getOrDefault(emptyList()) }
                tasks.value = withContext(Dispatchers.IO) { runCatching { db.tasks(acc.id) }.getOrDefault(emptyList()) }
                friends.value = withContext(Dispatchers.IO) { runCatching { db.friends(acc.id).size }.getOrDefault(0) }
            } else {
                stats.value = ArchiveStats()
                sources.value = emptyList()
                tasks.value = emptyList()
                friends.value = 0
            }
        } finally {
            loading.value = false
        }
    }

    fun notify(message: String) {
        toast.value = message
    }

    fun consumeToast(): String {
        val t = toast.value
        toast.value = ""
        return t
    }

    /** 用当前会话创建或更新账号；数据库异常也不会让界面停在"未连接"。 */
    fun syncAccount(context: Context, uinOverride: String? = null): String? {
        val app = context.applicationContext
        val uin = uinOverride?.takeIf { it.isNotBlank() } ?: SessionStore.uin(app)
        if (uin.isBlank()) return null
        val id = "qq:$uin"
        val nick = SessionStore.nickname(app)
        val avatar = SessionStore.avatar(app)
        var acc = Account(id, uin, nick, avatar, System.currentTimeMillis(), 0)
        runCatching {
            val db = Db.get(app)
            val existing = db.account(id)
            acc = Account(
                id = id, uin = uin,
                nickname = nick.ifBlank { existing?.nickname ?: "" },
                avatar = avatar.ifBlank { existing?.avatar ?: "" },
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                lastSyncAt = existing?.lastSyncAt ?: 0,
            )
            db.upsertAccount(acc)
        }.onFailure { com.memora.app.core.Crash.record(app, it) }
        Prefs.setActiveAccount(id)
        account.value = acc
        return id
    }

    fun startTask(context: Context, type: String, label: String): String? {
        val acc = account.value ?: return null
        val db = Db.get(context.applicationContext)
        val id = "$type-${System.currentTimeMillis()}"
        db.createTask(
            SyncTask(
                id = id, accountId = acc.id, type = type, label = label,
                status = TaskStatus.PENDING, createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(), error = "", note = "",
            )
        )
        TaskEngine.start(context, id)
        return id
    }

    fun taskCount(type: String): Int = tasks.value.count { it.type == type && it.status.active }
}
