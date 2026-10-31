package com.memora.app.core.friend

import com.memora.app.core.Friend
import com.memora.app.core.db.Db

/**
 * 快照差异。判断非常保守：
 * 一次快照里没出现只记「疑似」，连续两次都没出现才升级为「已消失」。
 * 关系方向只有接口明确给出双向状态时才写，否则保持未知。
 */
object FriendDiff {

    data class Result(
        val seen: Int,
        val added: Int,
        val missing: Int,
        val gone: Int,
        val newFriends: List<Friend>,
        val goneFriends: List<Friend>,
    )

    fun apply(db: Db, accountId: String, seenUins: Set<String>, runStart: Long, keep: Int): Result {
        val previousMisses = db.misses(accountId)
        var missing = 0
        var gone = 0
        val newlyGone = ArrayList<Friend>()

        for (f in db.friends(accountId)) {
            if (f.uin in seenUins) continue
            val m = (previousMisses[f.uin] ?: 0) + 1
            db.recordMissing(accountId, f.uin, f.displayName, System.currentTimeMillis(), "snap-$runStart", m)
            if (m >= 2) { gone++; newlyGone.add(f) } else missing++
        }

        val newFriends = db.friends(accountId).filter { it.firstSeenAt >= runStart }
        db.snapshot(accountId, runStart, "friend", seenUins.size, seenUins.hashCode().toString())
        db.trimSnapshots(accountId, keep)
        return Result(seenUins.size, newFriends.size, missing, gone, newFriends, newlyGone)
    }
}
