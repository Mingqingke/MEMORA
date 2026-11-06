package com.memora.app.core.source

import com.memora.app.core.SourceStatus

/** 解析后的中间记录：来源适配器只负责把响应变成这些结构，不碰数据库。 */
data class CommentRec(
    val id: String,
    val authorUin: String,
    val authorName: String,
    val content: String,
    val time: Long?,
    val parentId: String = "",
)

data class MomentRec(
    val tid: String,
    val authorUin: String,
    val content: String,
    val createTime: Long?,
    val location: String = "",
    val pics: List<String> = emptyList(),
    val videos: List<String> = emptyList(),
    val comments: List<CommentRec> = emptyList(),
    val likes: List<Pair<String, String>> = emptyList(),
    val isPrivate: Boolean = false,
)

data class FriendRec(
    val uin: String,
    val nickname: String,
    val remark: String,
    val avatar: String,
    val groups: String,
    val addedAt: Long? = null,
)

data class AlbumRec(
    val albumId: String,
    val name: String,
    val count: Int,
    val cover: String,
    val desc: String,
    val createTime: Long?,
    val photos: List<Pair<String, String>> = emptyList(),
)

data class ActivityRec(
    val id: String,
    val actorUin: String,
    val actorName: String,
    val type: String,
    val content: String,
    val time: Long?,
    val targetTid: String,
)

/** 一页结果：状态、记录、下一页游标分开表达，空数组不等于完结。 */
data class FetchedPage(
    val status: SourceStatus,
    val detail: String = "",
    val moments: List<MomentRec> = emptyList(),
    val friends: List<FriendRec> = emptyList(),
    val albums: List<AlbumRec> = emptyList(),
    val activities: List<ActivityRec> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val rawCount: Int = 0,
    val unparsed: Int = 0,
) {
    val recordCount: Int
        get() = moments.size + friends.size + albums.size + activities.size
}
