package com.memora.app.core.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.memora.app.core.Account
import com.memora.app.core.Activity
import com.memora.app.core.Album
import com.memora.app.core.ArchiveStats
import com.memora.app.core.Checkpoint
import com.memora.app.core.Comment
import com.memora.app.core.Completeness
import com.memora.app.core.Confidence
import com.memora.app.core.EvidenceRow
import com.memora.app.core.Friend
import com.memora.app.core.MediaItem
import com.memora.app.core.Moment
import com.memora.app.core.MomentField
import com.memora.app.core.RecoveryState
import com.memora.app.core.SourceState
import com.memora.app.core.SourceStatus
import com.memora.app.core.SyncTask
import com.memora.app.core.TaskStat
import com.memora.app.core.TaskStatus
import com.memora.app.core.YearCount

/**
 * 本地档案库。所有写入都在 IO 线程，事务按「一页」提交：
 * 内容 + 字段来源 + 互动 + 检查点同事务落库，进程被杀不会出现游标跑在数据前面。
 */
class Db private constructor(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    companion object {
        private const val NAME = "memora_archive.db"
        private const val VERSION = 7
        @Volatile private var instance: Db? = null

        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                var db = Db(app)
                fun coreOk(d: Db): Boolean = runCatching {
                    d.readableDatabase.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='accounts'", null
                    ).use { it.moveToFirst() }
                }.getOrDefault(false)
                fun park() {
                    // 只改名保留，绝不直接删除用户档案
                    val f = app.getDatabasePath(NAME)
                    val stamp = System.currentTimeMillis()
                    runCatching { if (f.exists()) f.renameTo(java.io.File(f.path + ".bak-$stamp")) }
                    runCatching { java.io.File(f.path + "-wal").takeIf { it.exists() }?.renameTo(java.io.File(f.path + "-wal.bak-$stamp")) }
                    runCatching { java.io.File(f.path + "-shm").takeIf { it.exists() }?.renameTo(java.io.File(f.path + "-shm.bak-$stamp")) }
                }
                try {
                    db.writableDatabase
                    if (!coreOk(db)) {
                        // 先尝试原地补表，补不上才换一个新库（旧库留档）
                        runCatching { db.close() }
                        db = Db(app)
                        db.writableDatabase
                        if (!coreOk(db)) {
                            Log.e("MemoraDb", "core table missing after repair, parking old db")
                            runCatching { db.close() }
                            park()
                            db = Db(app)
                            db.writableDatabase
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("MemoraDb", "open failed, parking old db", t)
                    runCatching { db.close() }
                    park()
                    db = Db(app)
                    runCatching { db.writableDatabase }
                }
                db.also { instance = it }
            }
        }

        fun momentKey(accountId: String, tid: String) = "$accountId:$tid"

        /** 关掉当前实例，下次 get 会重新打开并自检（不删数据）。 */
        fun invalidate() {
            synchronized(this) {
                runCatching { instance?.close() }
                instance = null
            }
        }

        /** 结构损坏时的兜底：关库、删文件、重建。调用方必须先拿到用户确认。 */
        fun rebuild(context: Context) {
            synchronized(this) {
                runCatching { instance?.close() }
                instance = null
                val app = context.applicationContext
                val f = app.getDatabasePath(NAME)
                val stamp = System.currentTimeMillis()
                runCatching { if (f.exists()) f.renameTo(java.io.File(f.path + ".rebuild-$stamp")) }
                runCatching { java.io.File(f.path + "-wal").takeIf { it.exists() }?.delete() }
                runCatching { java.io.File(f.path + "-shm").takeIf { it.exists() }?.delete() }
            }
        }
    }

    init {
        // WAL 在 helper 层开启：onConfigure 里不能切换日志模式
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 任何一条建表语句失败都不应该让数据库打不开、应用不可用
        runCatching { createSchema(db) }.onFailure { Log.e("MemoraDb", "onCreate failed", it) }
    }

    /** 每次打开都补齐缺失的表与列：旧版本可能因为语句失败留下半套结构。 */
    override fun onOpen(db: SQLiteDatabase) {
        runCatching { createSchema(db) }.onFailure { Log.e("MemoraDb", "repair failed", it) }
        runCatching { db.execSQL("CREATE TABLE IF NOT EXISTS search_index(ref TEXT PRIMARY KEY, text TEXT NOT NULL)") }
        runCatching { repairColumns(db) }.onFailure { Log.e("MemoraDb", "repairColumns failed", it) }
    }

    /** 任何历史版本缺的列都补上，避免 no such column 直接把页面打崩。 */
    private fun repairColumns(db: SQLiteDatabase) {
        val specs = listOf(
            Triple("friends", "misses", "INTEGER NOT NULL DEFAULT 0"),
            Triple("friends", "addedAt", "INTEGER NOT NULL DEFAULT 0"),
            Triple("friends", "tags", "TEXT"),
            Triple("friends", "note", "TEXT"),
            Triple("friends", "relation", "INTEGER NOT NULL DEFAULT 0"),
            Triple("moment_media", "queuedAt", "INTEGER NOT NULL DEFAULT 0"),
            Triple("moment_media", "albumId", "TEXT"),
            Triple("moment_media", "caption", "TEXT"),
            Triple("moments", "visibility", "TEXT"),
            Triple("moments", "fieldCount", "INTEGER NOT NULL DEFAULT 0"),
            Triple("tasks", "note", "TEXT"),
            Triple("source_states", "records", "INTEGER NOT NULL DEFAULT 0"),
            Triple("authors", "posts", "INTEGER NOT NULL DEFAULT 0"),
            Triple("authors", "comments", "INTEGER NOT NULL DEFAULT 0"),
        )
        specs.forEach { (table, column, type) ->
            runCatching { ensureColumn(db, table, column, type) }
                .onFailure { Log.w("MemoraDb", "ensure $table.$column failed", it) }
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS accounts(
              id TEXT PRIMARY KEY, uin TEXT NOT NULL, nickname TEXT, avatar TEXT,
              createdAt INTEGER NOT NULL, lastSyncAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS friends(
              accountId TEXT NOT NULL, uin TEXT NOT NULL, nickname TEXT, remark TEXT, avatar TEXT,
              groups TEXT, relation INTEGER NOT NULL DEFAULT 0,
              firstSeenAt INTEGER NOT NULL, lastSeenAt INTEGER NOT NULL,
              addedAt INTEGER NOT NULL DEFAULT 0,
              goneState INTEGER NOT NULL DEFAULT 0, goneSinceAt INTEGER NOT NULL DEFAULT 0,
              snapshots INTEGER NOT NULL DEFAULT 1, misses INTEGER NOT NULL DEFAULT 0,
              tags TEXT, note TEXT,
              PRIMARY KEY(accountId, uin)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS snapshots(
              id TEXT PRIMARY KEY, accountId TEXT NOT NULL, takenAt INTEGER NOT NULL,
              sourceId TEXT NOT NULL, friendCount INTEGER NOT NULL, payloadHash TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS friend_changes(
              id TEXT PRIMARY KEY, accountId TEXT NOT NULL, uin TEXT NOT NULL, name TEXT,
              changeType TEXT NOT NULL, oldValue TEXT, newValue TEXT, detectedAt INTEGER NOT NULL,
              snapshotId TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_friend_changes ON friend_changes(accountId, detectedAt)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS moments(
              id TEXT PRIMARY KEY, accountId TEXT NOT NULL, tid TEXT NOT NULL, authorUin TEXT,
              content TEXT, createTime INTEGER, location TEXT, visibility TEXT,
              state TEXT NOT NULL, completeness INTEGER NOT NULL, confidence INTEGER NOT NULL,
              fieldCount INTEGER NOT NULL DEFAULT 0,
              createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_moments_time ON moments(accountId, createTime)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_moments_state ON moments(accountId, state)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS moment_fields(
              momentId TEXT NOT NULL, field TEXT NOT NULL, sourceId TEXT NOT NULL,
              capturedAt INTEGER NOT NULL, confidence INTEGER NOT NULL, digest TEXT,
              PRIMARY KEY(momentId, field, sourceId)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS moment_media(
              id TEXT PRIMARY KEY, momentId TEXT NOT NULL, url TEXT NOT NULL, localPath TEXT,
              kind INTEGER NOT NULL, mime TEXT, fileSize INTEGER NOT NULL DEFAULT 0,
              hash TEXT, state INTEGER NOT NULL DEFAULT 0, bytes INTEGER NOT NULL DEFAULT 0,
              caption TEXT, albumId TEXT, queuedAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_moment ON moment_media(momentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_state ON moment_media(state)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS comments(
              id TEXT PRIMARY KEY, momentId TEXT NOT NULL, authorUin TEXT, authorName TEXT,
              content TEXT, createTime INTEGER, parentId TEXT, sourceId TEXT NOT NULL,
              capturedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_comments_moment ON comments(momentId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS activities(
              id TEXT PRIMARY KEY, accountId TEXT NOT NULL, actorUin TEXT, actorName TEXT,
              type TEXT NOT NULL, content TEXT, time INTEGER, targetTid TEXT,
              sourceId TEXT NOT NULL, capturedAt INTEGER NOT NULL, linkedMomentId TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_activities_tid ON activities(accountId, targetTid)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS albums(
              id TEXT PRIMARY KEY, accountId TEXT NOT NULL, name TEXT, count INTEGER NOT NULL DEFAULT 0,
              coverUrl TEXT, desc TEXT, createTime INTEGER, sourceId TEXT NOT NULL, capturedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS source_states(
              accountId TEXT NOT NULL, sourceId TEXT NOT NULL, stream TEXT NOT NULL,
              status TEXT NOT NULL, detail TEXT, lastProbeAt INTEGER NOT NULL DEFAULT 0,
              lastOkAt INTEGER NOT NULL DEFAULT 0, records INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY(accountId, sourceId, stream)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tasks(
              id TEXT PRIMARY KEY, accountId TEXT NOT NULL, type TEXT NOT NULL, label TEXT,
              status TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
              error TEXT, note TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS task_stats(
              taskId TEXT PRIMARY KEY, fetched INTEGER NOT NULL DEFAULT 0, inserted INTEGER NOT NULL DEFAULT 0,
              updated INTEGER NOT NULL DEFAULT 0, duplicates INTEGER NOT NULL DEFAULT 0,
              failed INTEGER NOT NULL DEFAULT 0, mediaQueued INTEGER NOT NULL DEFAULT 0,
              mediaDone INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS checkpoints(
              taskId TEXT NOT NULL, sourceId TEXT NOT NULL, stream TEXT NOT NULL,
              cursor TEXT, offset INTEGER NOT NULL DEFAULT 0, lastRecordId TEXT,
              lastTimestamp INTEGER NOT NULL DEFAULT 0, pageCount INTEGER NOT NULL DEFAULT 0,
              recordCount INTEGER NOT NULL DEFAULT 0, failCount INTEGER NOT NULL DEFAULT 0,
              updatedAt INTEGER NOT NULL,
              PRIMARY KEY(taskId, sourceId, stream)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS search_index(ref TEXT PRIMARY KEY, text TEXT NOT NULL)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS logs(
              id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL,
              level TEXT NOT NULL, tag TEXT NOT NULL, message TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun ensureColumn(db: SQLiteDatabase, table: String, column: String, type: String) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            var found = false
            while (c.moveToNext()) {
                if (c.getString(1).equals(column, ignoreCase = true)) { found = true; break }
            }
            found
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 档案只增不删：按列增量迁移，不清空、不重建
        if (oldVersion < 2) {
            runCatching { ensureColumn(db, "moment_media", "queuedAt", "INTEGER NOT NULL DEFAULT 0") }
                .onFailure { Log.w("MemoraDb", "upgrade v2 failed", it) }
        }
        if (oldVersion < 3) {
            runCatching { ensureColumn(db, "friends", "addedAt", "INTEGER NOT NULL DEFAULT 0") }
                .onFailure { Log.w("MemoraDb", "upgrade v3 failed", it) }
        }
        if (oldVersion < 5) {
            runCatching { db.execSQL("DROP TABLE IF EXISTS search_fts") }
            runCatching { db.execSQL("CREATE TABLE IF NOT EXISTS search_index(ref TEXT PRIMARY KEY, text TEXT NOT NULL)") }
        }
        if (oldVersion < 6) {
            runCatching {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS authors(uin TEXT PRIMARY KEY, nickname TEXT, avatar TEXT," +
                        " firstSeenAt INTEGER NOT NULL, lastSeenAt INTEGER NOT NULL, sourceId TEXT," +
                        " posts INTEGER NOT NULL DEFAULT 0, comments INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }
        if (oldVersion < 7) {
            // v7：主来源改用 moment_fields 实时计算，不再依赖 moments 上的冗余列
            runCatching { db.execSQL("DROP INDEX IF EXISTS idx_moments_source") }
        }
    }

    /* ---------------- 账号 ---------------- */

    fun upsertAccount(a: Account) {
        val cv = ContentValues().apply {
            put("id", a.id); put("uin", a.uin); put("nickname", a.nickname); put("avatar", a.avatar)
            put("createdAt", a.createdAt); put("lastSyncAt", a.lastSyncAt)
        }
        writableDatabase.insertWithOnConflict("accounts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun account(id: String): Account? = readableDatabase.rawQuery(
        "SELECT id,uin,nickname,avatar,createdAt,lastSyncAt FROM accounts WHERE id=?", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) c.toAccount() else null }

    fun accounts(): List<Account> = readableDatabase.rawQuery(
        "SELECT id,uin,nickname,avatar,createdAt,lastSyncAt FROM accounts ORDER BY createdAt DESC", null
    ).use { c -> ArrayList<Account>().also { list -> while (c.moveToNext()) list.add(c.toAccount()) } }

    fun touchSync(accountId: String, at: Long) {
        writableDatabase.execSQL("UPDATE accounts SET lastSyncAt=? WHERE id=?", arrayOf<Any>(at, accountId))
    }

    private fun Cursor.toAccount() = Account(
        id = getString(0), uin = getString(1), nickname = getString(2).orEmpty(),
        avatar = getString(3).orEmpty(), createdAt = getLong(4), lastSyncAt = getLong(5),
    )

    /* ---------------- 好友 ---------------- */

    /**
     * 好友写入：首次发现时间只保留最早一次，昵称/备注/头像变化写 friend_changes，
     * 消失必须在快照比对里由 Diff 判定，这里只负责保留数据。
     */
    fun upsertFriend(
        accountId: String, uin: String, nickname: String, remark: String, avatar: String,
        groups: String, now: Long, addedAt: Long? = null,
    ): Boolean {
        val db = writableDatabase
        val existing = friend(accountId, uin)
        return if (existing == null) {
            val cv = ContentValues().apply {
                put("accountId", accountId); put("uin", uin); put("nickname", nickname)
                put("remark", remark); put("avatar", avatar); put("groups", groups)
                put("relation", 0); put("firstSeenAt", now); put("lastSeenAt", now)
                put("addedAt", addedAt ?: 0L)
                put("goneState", 0); put("goneSinceAt", 0); put("snapshots", 1)
                put("tags", ""); put("note", "")
            }
            db.insertWithOnConflict("friends", null, cv, SQLiteDatabase.CONFLICT_IGNORE) > 0
        } else {
            val cv = ContentValues().apply {
                put("nickname", nickname); put("remark", remark); put("avatar", avatar)
                put("groups", groups); put("lastSeenAt", now)
                put("snapshots", existing.snapshots + 1)
                if (addedAt != null && addedAt > 0) put("addedAt", addedAt)
                // 重新出现：回到在列，miss 计数清零，历史消失时间保留
                put("goneState", 0); put("misses", 0)
            }
            db.update("friends", cv, "accountId=? AND uin=?", arrayOf(accountId, uin))
            writeFriendDelta(accountId, existing, nickname, remark, groups, now)
            true
        }
    }

    private fun writeFriendDelta(
        accountId: String, old: Friend, nickname: String, remark: String, groups: String, now: Long,
    ) {
        if (old.nickname != nickname && nickname.isNotBlank()) {
            insertFriendChange(accountId, old.uin, old.displayName, "NICKNAME", old.nickname, nickname, now)
        }
        if (old.remark != remark) {
            insertFriendChange(accountId, old.uin, old.displayName, "REMARK", old.remark, remark, now)
        }
        if (old.groups != groups && groups.isNotBlank()) {
            insertFriendChange(accountId, old.uin, old.displayName, "GROUP", old.groups, groups, now)
        }
    }

    private fun insertFriendChange(
        accountId: String, uin: String, name: String, type: String,
        oldV: String, newV: String, now: Long, snapshotId: String = "",
    ) {
        val cv = ContentValues().apply {
            put("id", "$accountId:$uin:$type:$now"); put("accountId", accountId); put("uin", uin)
            put("name", name); put("changeType", type); put("oldValue", oldV); put("newValue", newV)
            put("detectedAt", now); put("snapshotId", snapshotId)
        }
        writableDatabase.insertWithOnConflict("friend_changes", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * 消失是「连续多份快照都没出现」才升级：第一次记疑似，第二次才记已消失。
     * 单次请求缺数据、权限变化或接口异常都不会把人判成删除。
     */
    fun recordMissing(accountId: String, uin: String, name: String, now: Long, snapshotId: String, misses: Int) {
        val hard = misses >= 2
        val cv = ContentValues().apply {
            put("goneState", if (hard) 2 else 1)
            put("misses", misses)
            if (hard) put("goneSinceAt", now)
        }
        writableDatabase.update("friends", cv, "accountId=? AND uin=?", arrayOf(accountId, uin))
        insertFriendChange(
            accountId, uin, name, if (hard) "GONE" else "MISSING", "在列",
            if (hard) "连续 $misses 次快照未出现" else "本次快照未出现", now, snapshotId,
        )
    }

    fun misses(accountId: String): Map<String, Int> = readableDatabase.rawQuery(
        "SELECT uin, misses FROM friends WHERE accountId=? AND goneState>0", arrayOf(accountId)
    ).use { c -> HashMap<String, Int>().also { m -> while (c.moveToNext()) m[c.getString(0)] = c.getInt(1) } }

    fun applyVerdict(id: String, state: RecoveryState, completeness: Completeness, confidence: Confidence) {
        val cv = ContentValues().apply {
            put("state", state.name); put("completeness", completeness.dots); put("confidence", confidence.ordinal)
            put("updatedAt", System.currentTimeMillis())
        }
        writableDatabase.update("moments", cv, "id=?", arrayOf(id))
    }

    fun friend(accountId: String, uin: String): Friend? = readableDatabase.rawQuery(
        "SELECT $FRIEND_COLS FROM friends WHERE accountId=? AND uin=?", arrayOf(accountId, uin)
    ).use { c -> if (c.moveToFirst()) c.toFriend() else null }

    fun friends(accountId: String, filter: String = "all"): List<Friend> {
        val where = when (filter) {
            "gone" -> "AND goneState>0"
            "recent" -> "AND firstSeenAt >= strftime('%s','now','-30 day')*1000"
            "changed" -> "AND uin IN (SELECT uin FROM friend_changes WHERE accountId=?)"
            else -> ""
        }
        val args = if (filter == "changed") arrayOf(accountId, accountId) else arrayOf(accountId)
        return readableDatabase.rawQuery(
            "SELECT $FRIEND_COLS FROM friends WHERE accountId=? $where ORDER BY lastSeenAt DESC", args
        ).use { c -> ArrayList<Friend>().also { l -> while (c.moveToNext()) l.add(c.toFriend()) } }
    }

    fun friendChanges(accountId: String, uin: String? = null, limit: Int = 300): List<Triple<String, String, Long>> {
        val sql = if (uin == null)
            "SELECT changeType, COALESCE(oldValue,'')||' → '||COALESCE(newValue,''), detectedAt FROM friend_changes WHERE accountId=? ORDER BY detectedAt DESC LIMIT $limit"
        else
            "SELECT changeType, COALESCE(oldValue,'')||' → '||COALESCE(newValue,''), detectedAt FROM friend_changes WHERE accountId=? AND uin=? ORDER BY detectedAt DESC LIMIT $limit"
        val args = if (uin == null) arrayOf(accountId) else arrayOf(accountId, uin)
        return readableDatabase.rawQuery(sql, args).use { c ->
            ArrayList<Triple<String, String, Long>>().also { l ->
                while (c.moveToNext()) l.add(Triple(c.getString(0), c.getString(1), c.getLong(2)))
            }
        }
    }

    fun snapshot(accountId: String, takenAt: Long, sourceId: String, count: Int, hash: String) {
        val cv = ContentValues().apply {
            put("id", "$accountId:$takenAt"); put("accountId", accountId); put("takenAt", takenAt)
            put("sourceId", sourceId); put("friendCount", count); put("payloadHash", hash)
        }
        writableDatabase.insertWithOnConflict("snapshots", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun trimSnapshots(accountId: String, keep: Int) {
        if (keep <= 0) return
        writableDatabase.execSQL(
            "DELETE FROM snapshots WHERE accountId=? AND id NOT IN " +
                "(SELECT id FROM snapshots WHERE accountId=? ORDER BY takenAt DESC LIMIT $keep)",
            arrayOf(accountId, accountId),
        )
    }

    fun snapshots(accountId: String): List<Triple<Long, Int, String>> = readableDatabase.rawQuery(
        "SELECT takenAt, friendCount, sourceId FROM snapshots WHERE accountId=? ORDER BY takenAt DESC", arrayOf(accountId)
    ).use { c ->
        ArrayList<Triple<Long, Int, String>>().also { l ->
            while (c.moveToNext()) l.add(Triple(c.getLong(0), c.getInt(1), c.getString(2)))
        }
    }

    /** 当前好友 UIN 集合，用于快照差异。 */
    fun presentUins(accountId: String): Set<String> = readableDatabase.rawQuery(
        "SELECT uin FROM friends WHERE accountId=? AND goneState=0", arrayOf(accountId)
    ).use { c -> HashSet<String>().also { s -> while (c.moveToNext()) s.add(c.getString(0)) } }

    fun friendsWithGoneState(accountId: String, state: Int): List<Friend> = readableDatabase.rawQuery(
        "SELECT $FRIEND_COLS FROM friends WHERE accountId=? AND goneState=?", arrayOf(accountId, state.toString())
    ).use { c -> ArrayList<Friend>().also { l -> while (c.moveToNext()) l.add(c.toFriend()) } }

    private fun Cursor.toFriend() = Friend(
        accountId = getString(0), uin = getString(1), nickname = getString(2).orEmpty(),
        remark = getString(3).orEmpty(), avatar = getString(4).orEmpty(), groups = getString(5).orEmpty(),
        relation = getInt(6), firstSeenAt = getLong(7), lastSeenAt = getLong(8), addedAt = getLong(9),
        goneState = getInt(10), goneSinceAt = getLong(11), snapshots = getInt(12), misses = getInt(13),
        tags = getString(14).orEmpty(), note = getString(15).orEmpty(),
    )

    fun setFriendMeta(accountId: String, uin: String, tags: String, note: String) {
        val cv = ContentValues().apply { put("tags", tags); put("note", note) }
        writableDatabase.update("friends", cv, "accountId=? AND uin=?", arrayOf(accountId, uin))
    }

    /* ---------------- 说说与字段级来源 ---------------- */

    /** 单页事务：内容 + 来源字段 + 评论 + 活动 + 媒体 + 检查点。 */
    fun commitPage(block: PageWriter.() -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            PageWriter(db).block()
            db.setTransactionSuccessful()
        } catch (t: Throwable) {
            Log.e("MemoraDb", "commitPage failed", t)
            throw t
        } finally {
            db.endTransaction()
        }
    }

    inner class PageWriter(private val db: SQLiteDatabase) {

        fun upsertMoment(
            accountId: String, tid: String, authorUin: String, content: String,
            createTime: Long?, location: String, state: RecoveryState,
            completeness: Completeness, confidence: Confidence, sourceId: String,
            fields: List<String>, now: Long,
        ): Boolean {
            if (tid.isBlank()) return false
            val id = momentKey(accountId, tid)
            val existing = momentById(id)
            val cv = ContentValues().apply {
                put("id", id); put("accountId", accountId); put("tid", tid)
                put("authorUin", authorUin)
                if (content.isNotBlank() || existing == null) put("content", content)
                put("createTime", createTime ?: existing?.createTime)
                if (location.isNotBlank()) put("location", location)
                put("state", state.name)
                put("completeness", completeness.dots)
                put("confidence", confidence.ordinal)
                put("createdAt", existing?.createdAt ?: now); put("updatedAt", now)
            }
            db.insertWithOnConflict("moments", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            val fcv = ContentValues()
            fields.forEach { f ->
                fcv.clear()
                fcv.put("momentId", id); fcv.put("field", f); fcv.put("sourceId", sourceId)
                fcv.put("capturedAt", now); fcv.put("confidence", confidence.ordinal)
                fcv.put("digest", "")
                db.insertWithOnConflict("moment_fields", null, fcv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val cnt = db.rawQuery("SELECT COUNT(DISTINCT field) FROM moment_fields WHERE momentId=?", arrayOf(id))
                .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val u = ContentValues().apply { put("fieldCount", cnt) }
            db.update("moments", u, "id=?", arrayOf(id))
            return existing == null
        }

        fun upsertComment(accountId: String, tid: String, comment: Comment) {
            val momentId = momentKey(accountId, tid)
            val cv = ContentValues().apply {
                put("id", comment.id); put("momentId", momentId)
                put("authorUin", comment.authorUin); put("authorName", comment.authorName)
                put("content", comment.content); put("createTime", comment.createTime)
                put("parentId", comment.parentId); put("sourceId", comment.sourceId)
                put("capturedAt", comment.capturedAt)
            }
            db.insertWithOnConflict("comments", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }

        fun upsertActivity(accountId: String, act: Activity) {
            val cv = ContentValues().apply {
                put("id", act.id); put("accountId", accountId); put("actorUin", act.actorUin)
                put("actorName", act.actorName); put("type", act.type); put("content", act.content)
                put("time", act.time); put("targetTid", act.targetTid); put("sourceId", act.sourceId)
                put("capturedAt", act.capturedAt)
                put("linkedMomentId", if (act.targetTid.isNotBlank()) momentKey(accountId, act.targetTid) else "")
            }
            db.insertWithOnConflict("activities", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }

        fun upsertMedia(accountId: String, tid: String, item: MediaItem) {
            val momentId = momentKey(accountId, tid)
            val cv = ContentValues().apply {
                put("id", item.id); put("momentId", momentId); put("url", item.url)
                put("localPath", item.localPath); put("kind", item.kind); put("mime", item.mime)
                put("fileSize", item.size); put("hash", item.hash); put("state", item.state)
                put("bytes", item.bytes); put("caption", item.caption); put("albumId", item.albumId)
            }
            db.insertWithOnConflict("moment_media", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }

        fun taskStat(taskId: String, fetched: Int, inserted: Int, duplicates: Int, failed: Int) {
            db.execSQL(
                "UPDATE task_stats SET fetched=fetched+?, inserted=inserted+?, duplicates=duplicates+?, failed=failed+? WHERE taskId=?",
                arrayOf<Any>(fetched, inserted, duplicates, failed, taskId),
            )
        }

        fun checkpoint(cp: Checkpoint) {
            val cv = ContentValues().apply {
                put("taskId", cp.taskId); put("sourceId", cp.sourceId); put("stream", cp.stream)
                put("cursor", cp.cursor); put("offset", cp.offset); put("lastRecordId", cp.lastRecordId)
                put("lastTimestamp", cp.lastTimestamp); put("pageCount", cp.pageCount)
                put("recordCount", cp.recordCount); put("failCount", cp.failCount)
                put("updatedAt", cp.updatedAt)
            }
            db.insertWithOnConflict("checkpoints", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun momentById(id: String): Moment? = readableDatabase.rawQuery(
        "SELECT $MOMENT_COLS, (SELECT COUNT(*) FROM comments c WHERE c.momentId=m.id), " +
            "(SELECT COUNT(*) FROM activities a WHERE a.linkedMomentId=m.id), " +
            "(SELECT COUNT(*) FROM moment_media mm WHERE mm.momentId=m.id) " +
            "FROM moments m WHERE m.id=?", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) c.toMoment() else null }

    fun moment(accountId: String, tid: String): Moment? = momentById(momentKey(accountId, tid))

    fun moments(
        accountId: String, year: Int? = null, state: String? = null, keyword: String? = null,
        limit: Int = 300, offset: Int = 0, source: String? = null,
    ): List<Moment> {
        val sb = StringBuilder("SELECT $MOMENT_COLS, (SELECT COUNT(*) FROM comments c WHERE c.momentId=m.id), ")
        sb.append("(SELECT COUNT(*) FROM activities a WHERE a.linkedMomentId=m.id), ")
        sb.append("(SELECT COUNT(*) FROM moment_media mm WHERE mm.momentId=m.id) FROM moments m WHERE m.accountId=?")
        val args = ArrayList<String>().apply { add(accountId) }
        if (year != null) {
            sb.append(" AND m.createTime>=? AND m.createTime<?")
            args.add(yearStart(year).toString()); args.add(yearStart(year + 1).toString())
        }
        if (!state.isNullOrBlank() && state != "all") { sb.append(" AND m.state=?"); args.add(state) }
        if (!source.isNullOrBlank() && source != "all") {
            sb.append(" AND m.id IN (SELECT momentId FROM moment_fields WHERE sourceId=?)")
            args.add(source)
        }
        if (!keyword.isNullOrBlank()) {
            sb.append(" AND (m.content LIKE ? OR m.tid LIKE ?)")
            args.add("%$keyword%"); args.add("%$keyword%")
        }
        sb.append(" ORDER BY m.createTime DESC, m.updatedAt DESC LIMIT $limit OFFSET $offset")
        return readableDatabase.rawQuery(sb.toString(), args.toTypedArray()).use { c ->
            ArrayList<Moment>().also { l -> while (c.moveToNext()) l.add(c.toMoment()) }
        }
    }

    fun momentCount(accountId: String): Int = scalar(
        "SELECT COUNT(*) FROM moments WHERE accountId=?", arrayOf(accountId)
    )

    fun momentCount(accountId: String, state: RecoveryState): Int = scalar(
        "SELECT COUNT(*) FROM moments WHERE accountId=? AND state=?", arrayOf(accountId, state.name)
    )

    fun comments(momentId: String): List<Comment> = readableDatabase.rawQuery(
        "SELECT id,momentId,authorUin,authorName,content,createTime,parentId,sourceId,capturedAt " +
            "FROM comments WHERE momentId=? ORDER BY COALESCE(createTime,0) ASC", arrayOf(momentId)
    ).use { c ->
        ArrayList<Comment>().also { l ->
            while (c.moveToNext()) l.add(
                Comment(
                    id = c.getString(0), momentId = c.getString(1), authorUin = c.getString(2).orEmpty(),
                    authorName = c.getString(3).orEmpty(), content = c.getString(4).orEmpty(),
                    createTime = if (c.isNull(5)) null else c.getLong(5), parentId = c.getString(6).orEmpty(),
                    sourceId = c.getString(7), capturedAt = c.getLong(8),
                )
            )
        }
    }

    fun momentFields(momentId: String): List<MomentField> = readableDatabase.rawQuery(
        "SELECT momentId,field,sourceId,capturedAt,confidence FROM moment_fields WHERE momentId=? ORDER BY field",
        arrayOf(momentId)
    ).use { c ->
        ArrayList<MomentField>().also { l ->
            while (c.moveToNext()) l.add(
                MomentField(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4))
            )
        }
    }

    /** 证据：把字段来源翻译成人能读的一行。 */
    fun evidence(momentId: String): List<EvidenceRow> {
        val srcNames = com.memora.app.core.source.SourceRegistry.names()
        return momentFields(momentId).map {
            EvidenceRow(
                field = it.field, sourceId = it.sourceId, capturedAt = it.capturedAt,
                confidence = it.confidence,
                detail = srcNames[it.sourceId] ?: it.sourceId,
            )
        }
    }

    fun activities(accountId: String, limit: Int = 200): List<Activity> = readableDatabase.rawQuery(
        "SELECT id,accountId,actorUin,actorName,type,content,time,targetTid,sourceId,capturedAt,linkedMomentId " +
            "FROM activities WHERE accountId=? ORDER BY COALESCE(time, capturedAt) DESC LIMIT $limit", arrayOf(accountId)
    ).use { c ->
        ArrayList<Activity>().also { l ->
            while (c.moveToNext()) l.add(
                Activity(
                    id = c.getString(0), accountId = c.getString(1), actorUin = c.getString(2).orEmpty(),
                    actorName = c.getString(3).orEmpty(), type = c.getString(4), content = c.getString(5).orEmpty(),
                    time = if (c.isNull(6)) null else c.getLong(6), targetTid = c.getString(7).orEmpty(),
                    sourceId = c.getString(8), capturedAt = c.getLong(9), linkedMomentId = c.getString(10).orEmpty(),
                )
            )
        }
    }

    fun activitiesFor(momentId: String): List<Activity> = readableDatabase.rawQuery(
        "SELECT id,accountId,actorUin,actorName,type,content,time,targetTid,sourceId,capturedAt,linkedMomentId " +
            "FROM activities WHERE linkedMomentId=? ORDER BY COALESCE(time, capturedAt) DESC", arrayOf(momentId)
    ).use { c ->
        ArrayList<Activity>().also { l ->
            while (c.moveToNext()) l.add(
                Activity(
                    id = c.getString(0), accountId = c.getString(1), actorUin = c.getString(2).orEmpty(),
                    actorName = c.getString(3).orEmpty(), type = c.getString(4), content = c.getString(5).orEmpty(),
                    time = if (c.isNull(6)) null else c.getLong(6), targetTid = c.getString(7).orEmpty(),
                    sourceId = c.getString(8), capturedAt = c.getLong(9), linkedMomentId = c.getString(10).orEmpty(),
                )
            )
        }
    }

    fun activitiesByActor(accountId: String, actorUin: String, limit: Int = 30): List<Activity> =
        readableDatabase.rawQuery(
            "SELECT id,accountId,actorUin,actorName,type,content,time,targetTid,sourceId,capturedAt,linkedMomentId " +
                "FROM activities WHERE accountId=? AND actorUin=? ORDER BY COALESCE(time, capturedAt) DESC LIMIT $limit",
            arrayOf(accountId, actorUin)
        ).use { c ->
            ArrayList<Activity>().also { l ->
                while (c.moveToNext()) l.add(
                    Activity(
                        id = c.getString(0), accountId = c.getString(1), actorUin = c.getString(2).orEmpty(),
                        actorName = c.getString(3).orEmpty(), type = c.getString(4), content = c.getString(5).orEmpty(),
                        time = if (c.isNull(6)) null else c.getLong(6), targetTid = c.getString(7).orEmpty(),
                        sourceId = c.getString(8), capturedAt = c.getLong(9), linkedMomentId = c.getString(10).orEmpty(),
                    )
                )
            }
        }

    fun media(momentId: String): List<MediaItem> = readableDatabase.rawQuery(
        "SELECT id,momentId,url,localPath,kind,mime,fileSize,hash,state,bytes,caption,albumId,queuedAt " +
            "FROM moment_media WHERE momentId=? ORDER BY id", arrayOf(momentId)
    ).use { c -> ArrayList<MediaItem>().also { l -> while (c.moveToNext()) l.add(c.toMedia()) } }

    fun pendingMedia(limit: Int = 40): List<MediaItem> = readableDatabase.rawQuery(
        "SELECT id,momentId,url,localPath,kind,mime,fileSize,hash,state,bytes,caption,albumId,queuedAt " +
            "FROM moment_media WHERE state IN (0,3) ORDER BY state DESC, id LIMIT $limit", null
    ).use { c -> ArrayList<MediaItem>().also { l -> while (c.moveToNext()) l.add(c.toMedia()) } }

    fun mediaOk(momentId: String): Int = scalar(
        "SELECT COUNT(*) FROM moment_media WHERE momentId=? AND state=2", arrayOf(momentId)
    )

    private fun Cursor.toMedia() = MediaItem(
        id = getString(0), momentId = getString(1), url = getString(2).orEmpty(),
        localPath = getString(3).orEmpty(), kind = getInt(4), mime = getString(5).orEmpty(),
        size = getLong(6), hash = getString(7).orEmpty(), state = getInt(8), bytes = getLong(9),
        caption = getString(10).orEmpty(), albumId = getString(11).orEmpty(),
        queuedAt = if (columnCount > 12) getLong(12) else 0,
    )

    fun updateMediaState(id: String, state: Int, localPath: String = "", bytes: Long = 0, size: Long = 0, hash: String = "") {
        val cv = ContentValues().apply {
            put("state", state)
            if (localPath.isNotBlank()) put("localPath", localPath)
            if (bytes > 0) put("bytes", bytes)
            if (size > 0) put("fileSize", size)
            if (hash.isNotBlank()) put("hash", hash)
        }
        writableDatabase.update("moment_media", cv, "id=?", arrayOf(id))
    }

    fun resetDownloadedMedia(): Int {
        val cv = ContentValues().apply {
            put("state", 0); put("localPath", ""); put("bytes", 0); put("hash", "")
        }
        return writableDatabase.update("moment_media", cv, "state=2", null)
    }

    fun mediaAsset(id: String): MediaItem? = readableDatabase.rawQuery(
        "SELECT id,momentId,url,localPath,kind,mime,fileSize,hash,state,bytes,caption,albumId,queuedAt FROM moment_media WHERE id=?",
        arrayOf(id)
    ).use { c -> if (c.moveToFirst()) c.toMedia() else null }

    /* ---------------- 相册 ---------------- */

    fun upsertAlbum(a: Album) {
        val cv = ContentValues().apply {
            put("id", a.id); put("accountId", a.accountId); put("name", a.name); put("count", a.count)
            put("coverUrl", a.coverUrl); put("desc", a.desc); put("createTime", a.createTime)
            put("sourceId", a.sourceId); put("capturedAt", a.capturedAt)
        }
        writableDatabase.insertWithOnConflict("albums", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun albums(accountId: String): List<Album> = readableDatabase.rawQuery(
        "SELECT id,accountId,name,count,coverUrl,desc,createTime,sourceId,capturedAt FROM albums WHERE accountId=? ORDER BY capturedAt DESC",
        arrayOf(accountId)
    ).use { c ->
        ArrayList<Album>().also { l ->
            while (c.moveToNext()) l.add(
                Album(c.getString(0), c.getString(1), c.getString(2).orEmpty(), c.getInt(3),
                    c.getString(4).orEmpty(), c.getString(5).orEmpty(),
                    if (c.isNull(6)) null else c.getLong(6), c.getString(7), c.getLong(8))
            )
        }
    }

    /* ---------------- 来源状态 ---------------- */

    fun putSourceState(s: SourceState) {
        val cv = ContentValues().apply {
            put("accountId", s.accountId); put("sourceId", s.sourceId); put("stream", s.stream)
            put("status", s.status.name); put("detail", s.detail); put("lastProbeAt", s.lastProbeAt)
            put("lastOkAt", s.lastOkAt); put("records", s.records)
        }
        writableDatabase.insertWithOnConflict("source_states", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun sourceStates(accountId: String): List<SourceState> = readableDatabase.rawQuery(
        "SELECT accountId,sourceId,stream,status,detail,lastProbeAt,lastOkAt,records FROM source_states WHERE accountId=? ORDER BY sourceId, stream",
        arrayOf(accountId)
    ).use { c ->
        ArrayList<SourceState>().also { l ->
            while (c.moveToNext()) l.add(
                SourceState(c.getString(0), c.getString(1), c.getString(2),
                    SourceStatus.entries.firstOrNull { it.name == c.getString(3) } ?: SourceStatus.UNKNOWN,
                    c.getString(4).orEmpty(), c.getLong(5), c.getLong(6), c.getInt(7))
            )
        }
    }

    /* ---------------- 任务 ---------------- */

    fun createTask(t: SyncTask) {
        val cv = ContentValues().apply {
            put("id", t.id); put("accountId", t.accountId); put("type", t.type); put("label", t.label)
            put("status", t.status.name); put("createdAt", t.createdAt); put("updatedAt", t.updatedAt)
            put("error", t.error); put("note", t.note)
        }
        writableDatabase.insertWithOnConflict("tasks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.execSQL("INSERT OR IGNORE INTO task_stats(taskId) VALUES(?)", arrayOf(t.id))
    }

    fun updateTask(id: String, status: TaskStatus? = null, error: String? = null, note: String? = null) {
        val cv = ContentValues().apply {
            put("updatedAt", System.currentTimeMillis())
            if (status != null) put("status", status.name)
            if (error != null) put("error", error)
            if (note != null) put("note", note)
        }
        writableDatabase.update("tasks", cv, "id=?", arrayOf(id))
    }

    fun task(id: String): SyncTask? = readableDatabase.rawQuery(
        "SELECT id,accountId,type,label,status,createdAt,updatedAt,error,note FROM tasks WHERE id=?", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) c.toTask() else null }

    fun tasks(accountId: String? = null, limit: Int = 60): List<SyncTask> {
        val sql = "SELECT id,accountId,type,label,status,createdAt,updatedAt,error,note FROM tasks" +
            (if (accountId != null) " WHERE accountId=?" else "") + " ORDER BY createdAt DESC LIMIT $limit"
        return readableDatabase.rawQuery(sql, accountId?.let { arrayOf(it) }).use { c ->
            ArrayList<SyncTask>().also { l -> while (c.moveToNext()) l.add(c.toTask()) }
        }
    }

    fun resumeCandidates(accountId: String): List<SyncTask> = readableDatabase.rawQuery(
        "SELECT id,accountId,type,label,status,createdAt,updatedAt,error,note FROM tasks " +
            "WHERE accountId=? AND status IN ('RUNNING','PENDING','PAUSED','RATE_LIMITED','WAITING_AUTH') ORDER BY createdAt DESC",
        arrayOf(accountId)
    ).use { c -> ArrayList<SyncTask>().also { l -> while (c.moveToNext()) l.add(c.toTask()) } }

    private fun Cursor.toTask() = SyncTask(
        id = getString(0), accountId = getString(1), type = getString(2), label = getString(3).orEmpty(),
        status = TaskStatus.of(getString(4)), createdAt = getLong(5), updatedAt = getLong(6),
        error = getString(7).orEmpty(), note = getString(8).orEmpty(),
    )

    fun stat(taskId: String): TaskStat = readableDatabase.rawQuery(
        "SELECT taskId,fetched,inserted,updated,duplicates,failed,mediaQueued,mediaDone FROM task_stats WHERE taskId=?",
        arrayOf(taskId)
    ).use { c ->
        if (c.moveToFirst()) TaskStat(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7))
        else TaskStat(taskId, 0, 0, 0, 0, 0, 0, 0)
    }

    fun checkpoints(taskId: String): List<Checkpoint> = readableDatabase.rawQuery(
        "SELECT taskId,sourceId,stream,cursor,offset,lastRecordId,lastTimestamp,pageCount,recordCount,failCount,updatedAt " +
            "FROM checkpoints WHERE taskId=? ORDER BY sourceId, stream", arrayOf(taskId)
    ).use { c ->
        ArrayList<Checkpoint>().also { l ->
            while (c.moveToNext()) l.add(
                Checkpoint(c.getString(0), c.getString(1), c.getString(2), c.getString(3) ?: "",
                    c.getInt(4), c.getString(5) ?: "", c.getLong(6), c.getInt(7), c.getInt(8), c.getInt(9), c.getLong(10))
            )
        }
    }

    fun checkpoint(taskId: String, sourceId: String, stream: String): Checkpoint? = readableDatabase.rawQuery(
        "SELECT taskId,sourceId,stream,cursor,offset,lastRecordId,lastTimestamp,pageCount,recordCount,failCount,updatedAt " +
            "FROM checkpoints WHERE taskId=? AND sourceId=? AND stream=?", arrayOf(taskId, sourceId, stream)
    ).use { c ->
        if (c.moveToFirst()) Checkpoint(c.getString(0), c.getString(1), c.getString(2), c.getString(3) ?: "",
            c.getInt(4), c.getString(5) ?: "", c.getLong(6), c.getInt(7), c.getInt(8), c.getInt(9), c.getLong(10))
        else null
    }

    /* ---------------- 统计与搜索 ---------------- */

    data class MediaTally(
        val imagesTotal: Int = 0, val imagesOk: Int = 0,
        val videosTotal: Int = 0, val videosOk: Int = 0,
        val failed: Int = 0, val pending: Int = 0,
    ) {
        val total get() = imagesTotal + videosTotal
        val ok get() = imagesOk + videosOk
        val rate: Float? get() = if (total == 0) null else ok.toFloat() / total
    }

    /** 归档窗口内的媒体统计（按入队时间，与任务时间窗对齐）。 */
    fun mediaTally(accountId: String, since: Long, until: Long = Long.MAX_VALUE): MediaTally {
        var imgTotal = 0; var imgOk = 0; var vidTotal = 0; var vidOk = 0; var failed = 0; var pending = 0
        readableDatabase.rawQuery(
            "SELECT kind, state, COUNT(*) FROM moment_media WHERE momentId LIKE ? AND queuedAt>=? AND queuedAt<=? " +
                "GROUP BY kind, state",
            arrayOf("$accountId:%", since.toString(), until.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val kind = c.getInt(0); val state = c.getInt(1); val n = c.getInt(2)
                if (kind == 1) { vidTotal += n; if (state == 2) vidOk += n } else { imgTotal += n; if (state == 2) imgOk += n }
                if (state == 3 || state == 4) failed += n
                if (state == 0 || state == 1) pending += n
            }
        }
        return MediaTally(imgTotal, imgOk, vidTotal, vidOk, failed, pending)
    }

    /** UIN → 可读名字：优先好友备注，其次昵称，最后退回 QQ 号。 */
    fun displayName(accountId: String, uin: String): String {
        if (uin.isBlank()) return ""
        val f = runCatching { friend(accountId, uin) }.getOrNull()
        val name = f?.let { it.remark.ifBlank { it.nickname } }.orEmpty()
        return name.ifBlank { uin }
    }

    /** 某条内容下参与互动的用户（去重）。 */
    fun interactorUins(momentId: String, limit: Int = 20): List<Pair<String, String>> =
        readableDatabase.rawQuery(
            "SELECT actorUin, actorName FROM activities WHERE linkedMomentId=? AND actorUin<>'' " +
                "GROUP BY actorUin ORDER BY MAX(capturedAt) DESC LIMIT $limit",
            arrayOf(momentId),
        ).use { c -> ArrayList<Pair<String, String>>().also { l -> while (c.moveToNext()) l.add(c.getString(0) to c.getString(1).orEmpty()) } }

    fun searchFriends(accountId: String, q: String, limit: Int = 40): List<Friend> = readableDatabase.rawQuery(
        "SELECT $FRIEND_COLS FROM friends WHERE accountId=? AND (nickname LIKE ? OR remark LIKE ? OR uin LIKE ?) " +
            "ORDER BY lastSeenAt DESC LIMIT $limit",
        arrayOf(accountId, "%$q%", "%$q%", "%$q%"),
    ).use { c -> ArrayList<Friend>().also { l -> while (c.moveToNext()) l.add(c.toFriend()) } }

    fun searchComments(accountId: String, q: String, limit: Int = 40): List<Comment> = readableDatabase.rawQuery(
        "SELECT id,momentId,authorUin,authorName,content,createTime,parentId,sourceId,capturedAt " +
            "FROM comments WHERE momentId LIKE ? AND content LIKE ? ORDER BY COALESCE(createTime,0) DESC LIMIT $limit",
        arrayOf("$accountId:%", "%$q%"),
    ).use { c ->
        ArrayList<Comment>().also { l ->
            while (c.moveToNext()) l.add(
                Comment(
                    id = c.getString(0), momentId = c.getString(1), authorUin = c.getString(2).orEmpty(),
                    authorName = c.getString(3).orEmpty(), content = c.getString(4).orEmpty(),
                    createTime = if (c.isNull(5)) null else c.getLong(5), parentId = c.getString(6).orEmpty(),
                    sourceId = c.getString(7), capturedAt = c.getLong(8),
                )
            )
        }
    }

    fun stats(accountId: String): ArchiveStats {
        val friends = scalar("SELECT COUNT(*) FROM friends WHERE accountId=? AND goneState=0", arrayOf(accountId))
        val gone = scalar("SELECT COUNT(*) FROM friends WHERE accountId=? AND goneState>0", arrayOf(accountId))
        val moments = scalar("SELECT COUNT(*) FROM moments WHERE accountId=?", arrayOf(accountId))
        val recovered = scalar(
            "SELECT COUNT(*) FROM moments WHERE accountId=? AND state IN ('RECOVERED_FULL','RECOVERED_PARTIAL','RECYCLE_BIN','INFERRED')",
            arrayOf(accountId)
        )
        val fragments = scalar(
            "SELECT COUNT(*) FROM moments WHERE accountId=? AND state IN ('INTERACTION_FRAGMENT','LOCAL_COPY','FRIEND_COPY')",
            arrayOf(accountId)
        )
        val comments = scalar("SELECT COUNT(*) FROM comments WHERE momentId LIKE ?", arrayOf("$accountId:%"))
        val acts = scalar("SELECT COUNT(*) FROM activities WHERE accountId=?", arrayOf(accountId))
        val albums = scalar("SELECT COUNT(*) FROM albums WHERE accountId=?", arrayOf(accountId))
        val media = scalar(
            "SELECT COUNT(*) FROM moment_media WHERE momentId LIKE ? AND state=2", arrayOf("$accountId:%")
        )
        val bytes = longScalar(
            "SELECT COALESCE(SUM(bytes),0) FROM moment_media WHERE momentId LIKE ? AND state=2", arrayOf("$accountId:%")
        )
        val bounds = readableDatabase.rawQuery(
            "SELECT MIN(createTime), MAX(createTime) FROM moments WHERE accountId=? AND createTime IS NOT NULL",
            arrayOf(accountId)
        ).use { c -> if (c.moveToFirst()) Pair(if (c.isNull(0)) null else c.getLong(0), if (c.isNull(1)) null else c.getLong(1)) else Pair(null, null) }
        val years = readableDatabase.rawQuery(
            "SELECT CAST(strftime('%Y', createTime/1000, 'unixepoch', 'localtime') AS INTEGER) y, COUNT(*) " +
                "FROM moments WHERE accountId=? AND createTime IS NOT NULL GROUP BY y ORDER BY y DESC", arrayOf(accountId)
        ).use { c ->
            ArrayList<YearCount>().also { l -> while (c.moveToNext()) l.add(YearCount(c.getInt(0), c.getInt(1))) }
        }
        return ArchiveStats(friends, gone, moments, recovered, fragments, comments, acts, albums, media, bytes,
            bounds.first, bounds.second, years)
    }

    /** 中文按子串匹配（SQLite 无内置分词，这里不谎称全文检索）。 */
    fun search(
        accountId: String, q: String, limit: Int = 80,
        from: Long? = null, to: Long? = null,
    ): List<Moment> {
        if (q.isBlank()) return emptyList()
        val sb = StringBuilder(
            "SELECT $MOMENT_COLS, (SELECT COUNT(*) FROM comments c WHERE c.momentId=m.id), " +
                "(SELECT COUNT(*) FROM activities a WHERE a.linkedMomentId=m.id), " +
                "(SELECT COUNT(*) FROM moment_media mm WHERE mm.momentId=m.id) FROM moments m " +
                "WHERE m.accountId=? AND (m.content LIKE ? OR m.tid LIKE ? OR m.location LIKE ?)"
        )
        val args = arrayListOf(accountId, "%$q%", "%$q%", "%$q%")
        if (from != null) { sb.append(" AND m.createTime>=?"); args.add(from.toString()) }
        if (to != null) { sb.append(" AND m.createTime<?"); args.add(to.toString()) }
        sb.append(" ORDER BY m.createTime DESC LIMIT $limit")
        return readableDatabase.rawQuery(sb.toString(), args.toTypedArray()).use { c ->
            ArrayList<Moment>().also { l -> while (c.moveToNext()) l.add(c.toMoment()) }
        }
    }

    private fun Cursor.toMoment() = Moment(
        id = getString(0), accountId = getString(1), tid = getString(2), authorUin = getString(3).orEmpty(),
        content = getString(4).orEmpty(), createTime = if (isNull(5)) null else getLong(5),
        location = getString(6).orEmpty(),
        state = RecoveryState.of(getString(7)),
        completeness = Completeness.of(getInt(8)), confidence = Confidence.of(getInt(9)),
        fieldCount = getInt(10), createdAt = getLong(11), updatedAt = getLong(12),
        commentCount = getInt(13), activityCount = getInt(14), mediaCount = getInt(15),
    )

    /* ---------------- 搜索索引与媒体中心 ---------------- */

    /** 中文按字加空格，FTS5 的 unicode61 才能命中子串。 */
    private fun spaced(text: String): String {
        val sb = StringBuilder(text.length * 2)
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF) {
                sb.append(' ').append(ch).append(' ')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun ftsQuery(q: String): String {
        val parts = q.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        return parts.joinToString(" ") { p ->
            if (p.any { it.code in 0x4E00..0x9FFF }) {
                "\"" + p.toCharArray().joinToString(" ") + "\""
            } else {
                "\"" + p.replace("\"", "") + "\""
            }
        }
    }

    /**
     * 作者资料：uin → 昵称/头像。界面永远不直接显示裸 QQ 号。
     * 有昵称就补上，没有就保留旧值，不覆盖成空。
     */
    fun upsertAuthor(uin: String, nickname: String, avatar: String, sourceId: String, now: Long, isPost: Boolean, isComment: Boolean) {
        if (uin.isBlank()) return
        runCatching {
            val db = writableDatabase
            val old = author(uin)
            val cv = ContentValues().apply {
                put("uin", uin)
                put("nickname", if (nickname.isNotBlank()) nickname else old?.nickname.orEmpty())
                put("avatar", if (avatar.isNotBlank()) avatar else old?.avatar.orEmpty())
                put("firstSeenAt", old?.firstSeenAt ?: now)
                put("lastSeenAt", now)
                put("sourceId", sourceId)
                put("posts", (old?.posts ?: 0) + if (isPost) 1 else 0)
                put("comments", (old?.comments ?: 0) + if (isComment) 1 else 0)
            }
            db.insertWithOnConflict("authors", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun author(uin: String): com.memora.app.core.Author? = runCatching {
        readableDatabase.rawQuery(
            "SELECT uin,nickname,avatar,firstSeenAt,lastSeenAt,sourceId,posts,comments FROM authors WHERE uin=?",
            arrayOf(uin)
        ).use { c ->
            if (c.moveToFirst()) com.memora.app.core.Author(
                c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(),
                c.getLong(3), c.getLong(4), c.getString(5).orEmpty(), c.getInt(6), c.getInt(7),
            ) else null
        }
    }.getOrNull()

    fun authors(limit: Int = 500): List<com.memora.app.core.Author> = runCatching {
        readableDatabase.rawQuery(
            "SELECT uin,nickname,avatar,firstSeenAt,lastSeenAt,sourceId,posts,comments FROM authors " +
                "ORDER BY comments+posts DESC LIMIT $limit", null
        ).use { c ->
            ArrayList<com.memora.app.core.Author>().also { l ->
                while (c.moveToNext()) l.add(
                    com.memora.app.core.Author(
                        c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(),
                        c.getLong(3), c.getLong(4), c.getString(5).orEmpty(), c.getInt(6), c.getInt(7),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun searchAuthors(q: String, limit: Int = 40): List<com.memora.app.core.Author> = runCatching {
        readableDatabase.rawQuery(
            "SELECT uin,nickname,avatar,firstSeenAt,lastSeenAt,sourceId,posts,comments FROM authors " +
                "WHERE nickname LIKE ? OR uin LIKE ? ORDER BY lastSeenAt DESC LIMIT $limit",
            arrayOf("%$q%", "%$q%")
        ).use { c ->
            ArrayList<com.memora.app.core.Author>().also { l ->
                while (c.moveToNext()) l.add(
                    com.memora.app.core.Author(
                        c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(),
                        c.getLong(3), c.getLong(4), c.getString(5).orEmpty(), c.getInt(6), c.getInt(7),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun reindexMoment(momentId: String) {
        runCatching {
            val m = momentById(momentId) ?: return
            val body = buildString {
                append(m.content).append(' ')
                comments(momentId).take(200).forEach { append(it.content).append(' ') }
            }
            if (body.isBlank()) {
                writableDatabase.delete("search_index", "ref=?", arrayOf(momentId))
            } else {
                val cv = ContentValues().apply { put("ref", momentId); put("text", body) }
                writableDatabase.insertWithOnConflict("search_index", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun reindexAccount(accountId: String): Int {
        var n = 0
        runCatching {
            writableDatabase.delete("search_index", "ref LIKE ?", arrayOf("$accountId:%"))
            val ids = readableDatabase.rawQuery("SELECT id FROM moments WHERE accountId=?", arrayOf(accountId))
                .use { c -> ArrayList<String>().also { l -> while (c.moveToNext()) l.add(c.getString(0)) } }
            ids.forEach { reindexMoment(it); n++ }
        }
        return n
    }

    fun searchIndexed(
        accountId: String, q: String, limit: Int = 120,
        from: Long? = null, to: Long? = null,
    ): List<Moment> {
        if (q.isBlank()) return emptyList()
        val ids = runCatching {
            readableDatabase.rawQuery(
                "SELECT ref FROM search_index WHERE text LIKE ? AND ref LIKE ? LIMIT $limit",
                arrayOf("%$q%", "$accountId:%"),
            ).use { c -> ArrayList<String>().also { l -> while (c.moveToNext()) l.add(c.getString(0)) } }
        }.getOrDefault(emptyList())
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<Moment>(ids.size)
        ids.forEach { id ->
            val m = runCatching { momentById(id) }.getOrNull() ?: return@forEach
            val t = m.createTime
            if (from != null && (t == null || t < from)) return@forEach
            if (to != null && (t == null || t >= to)) return@forEach
            out.add(m)
        }
        return out.sortedByDescending { it.createTime ?: 0L }
    }

    data class MediaRow(
        val id: String, val url: String, val localPath: String, val kind: Int, val state: Int,
        val bytes: Long, val size: Long, val hash: String, val momentId: String,
        val time: Long?, val caption: String,
    )

    fun mediaRows(accountId: String, kind: Int?, onlyBroken: Boolean, limit: Int, offset: Int): List<MediaRow> {
        val sb = StringBuilder(
            "SELECT mm.id,mm.url,mm.localPath,mm.kind,mm.state,mm.bytes,mm.fileSize,mm.hash,mm.momentId,m.createTime,mm.caption " +
                "FROM moment_media mm LEFT JOIN moments m ON m.id=mm.momentId WHERE mm.momentId LIKE ?"
        )
        val args = arrayListOf("$accountId:%")
        if (kind != null) { sb.append(" AND mm.kind=?"); args.add(kind.toString()) }
        if (onlyBroken) sb.append(" AND mm.state IN (2,3,4) AND (mm.localPath='' OR mm.hash='' OR mm.bytes=0)")
        sb.append(" ORDER BY COALESCE(m.createTime,0) DESC LIMIT $limit OFFSET $offset")
        return readableDatabase.rawQuery(sb.toString(), args.toTypedArray()).use { c ->
            ArrayList<MediaRow>().also { l ->
                while (c.moveToNext()) l.add(
                    MediaRow(
                        id = c.getString(0), url = c.getString(1).orEmpty(), localPath = c.getString(2).orEmpty(),
                        kind = c.getInt(3), state = c.getInt(4), bytes = c.getLong(5), size = c.getLong(6),
                        hash = c.getString(7).orEmpty(), momentId = c.getString(8),
                        time = if (c.isNull(9)) null else c.getLong(9), caption = c.getString(10).orEmpty(),
                    )
                )
            }
        }
    }

    fun mediaCountByState(accountId: String): Map<Int, Int> = readableDatabase.rawQuery(
        "SELECT state, COUNT(*) FROM moment_media WHERE momentId LIKE ? GROUP BY state", arrayOf("$accountId:%")
    ).use { c -> HashMap<Int, Int>().also { m -> while (c.moveToNext()) m[c.getInt(0)] = c.getInt(1) } }

    fun mediaMarkedDone(accountId: String, limit: Int = 2000): List<MediaRow> = readableDatabase.rawQuery(
        "SELECT mm.id,mm.url,mm.localPath,mm.kind,mm.state,mm.bytes,mm.fileSize,mm.hash,mm.momentId,m.createTime,mm.caption " +
            "FROM moment_media mm LEFT JOIN moments m ON m.id=mm.momentId " +
            "WHERE mm.momentId LIKE ? AND mm.state=2 LIMIT $limit", arrayOf("$accountId:%")
    ).use { c ->
        ArrayList<MediaRow>().also { l ->
            while (c.moveToNext()) l.add(
                MediaRow(
                    c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3), c.getInt(4),
                    c.getLong(5), c.getLong(6), c.getString(7).orEmpty(), c.getString(8),
                    if (c.isNull(9)) null else c.getLong(9), c.getString(10).orEmpty(),
                )
            )
        }
    }

    fun ftsCount(accountId: String): Int = runCatching {
        scalar("SELECT COUNT(*) FROM search_index WHERE ref LIKE ?", arrayOf("$accountId:%"))
    }.getOrDefault(0)

    fun scalar(sql: String, args: Array<String>?): Int = readableDatabase.rawQuery(sql, args)
        .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun longScalar(sql: String, args: Array<String>?): Long = readableDatabase.rawQuery(sql, args)
        .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    fun log(level: String, tag: String, message: String) {
        val cv = ContentValues().apply {
            put("at", System.currentTimeMillis()); put("level", level); put("tag", tag); put("message", message)
        }
        runCatching { writableDatabase.insert("logs", null, cv) }
    }

    fun logs(limit: Int = 200): List<Triple<Long, String, String>> = runCatching { logsRaw(limit) }.getOrDefault(emptyList())

    private fun logsRaw(limit: Int): List<Triple<Long, String, String>> = readableDatabase.rawQuery(
        "SELECT at, level, tag||' · '||message FROM logs ORDER BY id DESC LIMIT $limit", null
    ).use { c ->
        ArrayList<Triple<Long, String, String>>().also { l ->
            while (c.moveToNext()) l.add(Triple(c.getLong(0), c.getString(1), c.getString(2)))
        }
    }

    fun clearAccount(accountId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("checkpoints", "taskId IN (SELECT id FROM tasks WHERE accountId=?)", arrayOf(accountId))
            db.delete("moment_fields", "momentId LIKE ?", arrayOf("$accountId:%"))
            db.delete("comments", "momentId LIKE ?", arrayOf("$accountId:%"))
            db.delete("moment_media", "momentId LIKE ?", arrayOf("$accountId:%"))
            db.delete("activities", "accountId=?", arrayOf(accountId))
            db.delete("moments", "accountId=?", arrayOf(accountId))
            listOf("friends", "snapshots", "friend_changes", "albums", "source_states", "tasks").forEach { t ->
                db.delete(t, "accountId=?", arrayOf(accountId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun yearStart(year: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.clear(); cal.set(year, java.util.Calendar.JANUARY, 1, 0, 0, 0)
        return cal.timeInMillis
    }
}

private const val FRIEND_COLS =
    "accountId,uin,nickname,remark,avatar,groups,relation,firstSeenAt,lastSeenAt,addedAt,goneState,goneSinceAt,snapshots,misses,tags,note"

private const val MOMENT_COLS =
    "m.id,m.accountId,m.tid,m.authorUin,m.content,m.createTime,m.location,m.state,m.completeness,m.confidence,m.fieldCount,m.createdAt,m.updatedAt"
