package com.memora.app.core.recovery

/**
 * 归属判定：一条记录是不是「本人发布的原始说说」。
 *
 * 规则来自实际踩过的坑：
 * - 互动卡片的作者字段是互动发起人，不是说说作者；
 * - 没有作者信息时不能默认算本人；
 * - 只有当前空间列表（current）里的记录可以直接当成本人内容。
 */
object Identity {

    /** 作者明确不是本人。 */
    fun isForeign(author: String, selfUin: String): Boolean =
        author.isNotBlank() && selfUin.isNotBlank() && author != selfUin

    /**
     * 这条记录只能作为线索（不能当说说主体）。
     * @param kind 解析层的标记：post / clue
     */
    fun treatAsClue(kind: String, author: String, selfUin: String, sourceId: String): Boolean {
        if (kind == "clue") return true
        if (sourceId == "current") return false
        if (author.isBlank()) return true
        return isForeign(author, selfUin)
    }
}
