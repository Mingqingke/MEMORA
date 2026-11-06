package com.memora.app.core

/* ============ 领域模型：恢复状态、完整度、可信度全部是独立维度 ============ */

enum class RecoveryState(val label: String, val note: String) {
    CURRENT("当前可见", "仍在当前可访问列表中"),
    RECYCLE_BIN("回收站", "来自官方回收站入口"),
    RECOVERED_FULL("完整恢复", "主体内容与主要互动均已找到"),
    RECOVERED_PARTIAL("部分恢复", "主体内容存在缺失字段"),
    INTERACTION_FRAGMENT("互动残留", "仅找到点赞 评论 回复等痕迹"),
    LOCAL_COPY("本地副本", "来自用户导入的旧备份"),
    FRIEND_COPY("好友侧副本", "来自有权访问的好友空间"),
    INFERRED("推断重建", "由多条证据交叉推断"),
    UNKNOWN("状态未知", "证据不足 无法判断"),
    UNAVAILABLE("未找到", "已扫描来源中均无记录");

    companion object {
        fun of(name: String?): RecoveryState = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

enum class Completeness(val label: String, val dots: Int) {
    FULL("完整", 4), MOSTLY("较完整", 3), PARTIAL("部分", 2), CLUE("线索", 1);

    companion object {
        fun of(v: Int): Completeness = when (v) {
            4 -> FULL; 3 -> MOSTLY; 2 -> PARTIAL; else -> CLUE
        }
        fun of(name: String?): Completeness = entries.firstOrNull { it.name == name } ?: CLUE
    }
}

enum class Confidence(val label: String) {
    HIGH("高"), MEDIUM("中"), LOW("低");

    companion object {
        fun of(v: Int): Confidence = when (v) {
            2 -> HIGH; 1 -> MEDIUM; else -> LOW
        }
        fun of(name: String?): Confidence = entries.firstOrNull { it.name == name } ?: LOW
    }
}

enum class SourceStatus(val label: String, val detail: String) {
    AVAILABLE("可用", "上次请求正常返回"),
    AUTH_REQUIRED("需要登录", "登录已失效"),
    RATE_LIMITED("稍后再试", "请求过于频繁"),
    BLOCKED("需要验证", "平台要求额外验证"),
    NOT_FOUND("暂不可用", "该入口已不存在"),
    UNSUPPORTED("不支持", "当前接口不返回"),
    SERVER_ERROR("暂时无法获取", "服务器没有正常返回"),
    PARSE_ERROR("格式无法识别", "内容格式变了"),
    EMPTY("暂无内容", "请求成功但没有记录"),
    UNKNOWN("未检测", "还没有试过");

    val usable: Boolean get() = this == AVAILABLE || this == EMPTY
    val needsUser: Boolean get() = this == AUTH_REQUIRED || this == BLOCKED
}

data class SourceProbe(
    val status: SourceStatus,
    val detail: String = "",
    val httpCode: Int = 0,
    val latencyMs: Long = 0,
)

data class Account(
    val id: String,
    val uin: String,
    val nickname: String,
    val avatar: String,
    val createdAt: Long,
    val lastSyncAt: Long,
)

data class Friend(
    val accountId: String,
    val uin: String,
    val nickname: String,
    val remark: String,
    val avatar: String,
    val groups: String,
    val relation: Int,          // 0 未知 / 1 双向 / 2 单向(待验证)
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val addedAt: Long,
    val goneState: Int,         // 0 在列 / 1 疑似消失 / 2 已消失
    val goneSinceAt: Long,
    val snapshots: Int,
    val misses: Int,
    val tags: String,
    val note: String,
) {
    val displayName: String get() = remark.ifBlank { nickname.ifBlank { uin } }
    val goneLabel: String
        get() = when (goneState) {
            1 -> "疑似消失";
            2 -> "已从列表消失";
            else -> "在列";
        }

    val goneHint: String
        get() = when (goneState) {
            1 -> "本次快照未出现 还不能判定被删除"
            2 -> "连续 $misses 次快照未出现 疑似已删除"
            else -> "在最近一次快照中"
        }
}

data class Moment(
    val id: String,
    val accountId: String,
    val tid: String,
    val authorUin: String,
    val content: String,
    val createTime: Long?,
    val location: String,
    val state: RecoveryState,
    val completeness: Completeness,
    val confidence: Confidence,
    val fieldCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val commentCount: Int = 0,
    val activityCount: Int = 0,
    val mediaCount: Int = 0,
    val primarySource: String = "",
)

data class MomentField(
    val momentId: String,
    val field: String,
    val sourceId: String,
    val capturedAt: Long,
    val confidence: Int,
)

data class Comment(
    val id: String,
    val momentId: String,
    val authorUin: String,
    val authorName: String,
    val content: String,
    val createTime: Long?,
    val parentId: String,
    val sourceId: String,
    val capturedAt: Long,
)

data class Activity(
    val id: String,
    val accountId: String,
    val actorUin: String,
    val actorName: String,
    val type: String,           // like / comment / reply / repost / message
    val content: String,
    val time: Long?,
    val targetTid: String,
    val sourceId: String,
    val capturedAt: Long,
    val linkedMomentId: String,
)

data class MediaItem(
    val id: String,
    val momentId: String,
    val url: String,
    val localPath: String,
    val kind: Int,              // 0 图片 / 1 视频
    val mime: String,
    val size: Long,
    val hash: String,
    val state: Int,             // 0 待下载 / 1 下载中 / 2 完成 / 3 失败 / 4 链接失效
    val bytes: Long,
    val caption: String,
    val albumId: String,
    val queuedAt: Long = 0,
)

data class Album(
    val id: String,
    val accountId: String,
    val name: String,
    val count: Int,
    val coverUrl: String,
    val desc: String,
    val createTime: Long?,
    val sourceId: String,
    val capturedAt: Long,
)

data class SourceState(
    val accountId: String,
    val sourceId: String,
    val stream: String,
    val status: SourceStatus,
    val detail: String,
    val lastProbeAt: Long,
    val lastOkAt: Long,
    val records: Int,
)

enum class TaskStatus(val label: String) {
    PENDING("等待中"), RUNNING("归档中"), PAUSED("已暂停"), WAITING_AUTH("需要重新登录"),
    RATE_LIMITED("稍后再试"), PARTIAL("部分完成"), COMPLETED("已完成"),
    FAILED("未完成"), CANCELLED("已取消");

    val active: Boolean get() = this == RUNNING || this == PENDING
    val done: Boolean get() = this == COMPLETED || this == PARTIAL || this == FAILED || this == CANCELLED

    companion object {
        fun of(name: String?): TaskStatus = entries.firstOrNull { it.name == name } ?: PENDING
    }
}

data class SyncTask(
    val id: String,
    val accountId: String,
    val type: String,           // friend / current / interaction / legacy / album / media / import / export
    val label: String,
    val status: TaskStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val error: String,
    val note: String,
)

data class Checkpoint(
    val taskId: String,
    val sourceId: String,
    val stream: String,
    val cursor: String,
    val offset: Int,
    val lastRecordId: String,
    val lastTimestamp: Long,
    val pageCount: Int,
    val recordCount: Int,
    val failCount: Int,
    val updatedAt: Long,
)

data class TaskStat(
    val taskId: String,
    val fetched: Int,
    val inserted: Int,
    val updated: Int,
    val duplicates: Int,
    val failed: Int,
    val mediaQueued: Int,
    val mediaDone: Int,
)

data class ArchiveStats(
    val friends: Int = 0,
    val goneFriends: Int = 0,
    val moments: Int = 0,
    val recovered: Int = 0,
    val fragments: Int = 0,
    val comments: Int = 0,
    val activities: Int = 0,
    val albums: Int = 0,
    val media: Int = 0,
    val mediaBytes: Long = 0,
    val firstMoment: Long? = null,
    val lastMoment: Long? = null,
    val years: List<YearCount> = emptyList(),
)

data class YearCount(val year: Int, val count: Int)

/** 一条内容拼装过程的完整解释：这是 MEMORA 与普通下载器的分界。 */
data class EvidenceRow(
    val field: String,
    val sourceId: String,
    val capturedAt: Long,
    val confidence: Int,
    val detail: String,
)

data class FieldGap(val field: String, val reason: String)


/** 作者资料：uin 与昵称/头像的映射，界面上永远不直接显示裸 uin。 */
data class Author(
    val uin: String,
    val nickname: String,
    val avatar: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val sourceId: String,
    val posts: Int,
    val comments: Int,
) {
    val displayName: String get() = nickname.ifBlank { "QQ $uin" }
}
