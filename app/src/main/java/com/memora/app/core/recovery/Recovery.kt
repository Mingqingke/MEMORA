package com.memora.app.core.recovery

import com.memora.app.core.Completeness
import com.memora.app.core.Confidence
import com.memora.app.core.FieldGap
import com.memora.app.core.Moment
import com.memora.app.core.RecoveryState
import com.memora.app.core.db.Db

/**
 * 恢复引擎：把「一个 tid 上的所有证据」重新折算成状态、完整度、可信度。
 * 三个维度互不替代：证据多不等于来源可靠，正文完整不等于互动齐全。
 */
object RecoveryEngine {

    val fieldLabels = mapOf(
        "body" to "正文",
        "time" to "发布时间",
        "media" to "图片或视频",
        "comments" to "评论",
        "like" to "点赞",
        "author" to "作者",
        "location" to "位置",
        "album" to "相册来源",
    )

    private val trust = mapOf(
        "current" to Confidence.HIGH,
        "friend" to Confidence.MEDIUM,
        "legacy" to Confidence.MEDIUM,
        "interaction" to Confidence.MEDIUM,
        "album" to Confidence.MEDIUM,
        "local" to Confidence.MEDIUM,
        "recycle" to Confidence.HIGH,
    )

    data class Verdict(
        val state: RecoveryState,
        val completeness: Completeness,
        val confidence: Confidence,
        val missing: List<FieldGap>,
        val sources: List<String>,
    )

    fun verdict(m: Moment, fields: Set<String>, comments: Int, activities: Int, sources: Set<String>): Verdict {
        val body = "body" in fields || m.content.isNotBlank()
        val time = "time" in fields || m.createTime != null
        val media = "media" in fields
        val hasComments = comments > 0 || "comments" in fields
        val hasActs = activities > 0 || "like" in fields
        val inter = hasComments || hasActs

        val state = when {
            "current" in sources -> RecoveryState.CURRENT
            "recycle" in sources -> RecoveryState.RECYCLE_BIN
            body && time && inter -> RecoveryState.RECOVERED_FULL
            body -> RecoveryState.RECOVERED_PARTIAL
            inter && sources.size >= 2 -> RecoveryState.INFERRED
            inter -> RecoveryState.INTERACTION_FRAGMENT
            "local" in sources -> RecoveryState.LOCAL_COPY
            sources.isEmpty() -> RecoveryState.UNKNOWN
            else -> RecoveryState.UNAVAILABLE
        }

        // 完整度只看「主体是否齐全 + 互动是否有落点」，不因为一条纯文字说说没有图片就降级
        val completeness = when {
            body && time && inter -> Completeness.FULL
            body && time -> Completeness.MOSTLY
            body && inter -> Completeness.MOSTLY
            body -> Completeness.PARTIAL
            else -> Completeness.CLUE
        }

        val confidence = sources.mapNotNull { trust[it] }.minByOrNull { it.ordinal }
            ?: if (sources.isNotEmpty()) Confidence.LOW else Confidence.LOW

        val missing = ArrayList<FieldGap>()
        if (!body) missing.add(FieldGap("正文", "任何来源都没有留下正文"))
        if (!time) missing.add(FieldGap("发布时间", "互动记录的时间不能当作发布时间"))
        if (!media) missing.add(FieldGap("图片或视频", "没有可用的媒体地址"))
        if (!hasComments) missing.add(FieldGap("评论", "评论接口未返回该条内容"))
        if (!hasActs) missing.add(FieldGap("点赞", "没有点赞残留"))

        return Verdict(state, completeness, confidence, missing, sources.toList().sorted())
    }

    /** 一页写入后重算受影响的 tid；只读现有证据，不做任何猜测性补全。 */
    fun recompute(db: Db, accountId: String, tids: Collection<String>) {
        for (tid in tids.distinct()) {
            val m = db.moment(accountId, tid) ?: continue
            val fields = db.momentFields(m.id).map { it.field }.toSet()
            val sources = db.momentFields(m.id).map { it.sourceId }.toSet()
            val verdict = verdict(m, fields, m.commentCount, m.activityCount, sources)
            db.applyVerdict(m.id, verdict.state, verdict.completeness, verdict.confidence)
        }
    }
}
