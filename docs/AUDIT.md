# MEMORA 历史恢复链路审计（阶段 1–3）

审计时间：2026-11。仅审计与取证，未改动功能代码。

## 一、现有链路（请求 → 展示）

QzoneApi（网络）→ Source（来源抽象）→ Ingest.commit（入库）→ Recovery.verdict（状态折算）→ Db → UI

涉及文件：
- `core/net/QzoneApi.kt`：msgList / feedStream / pavAll / guestbook / likeList
- `core/source/Source.kt`：DiscoverSource / RelatedFeedSource / GuestbookSource / InteractionFeedSource / CurrentFeedsSource
- `core/recovery/Ingest.kt`：commit、占位记录
- `core/recovery/Recovery.kt`：verdict 状态折算
- `core/db/Db.kt`：moments / activities / moment_fields

## 二、已确认的缺陷（都能指出具体位置）

### 1. 留言被当作说说写入
`Source.kt` 的 `DiscoverSource.fetch` 调用 `QzoneApi.guestbook`，把返回的留言包成 `MomentRec(tid="gb:xxx")`，随后 `Ingest.commit` 无差别写进 `moments` 表。
后果：留言板内容出现在「说说」时间线里，作者是留言者。
这正是 QQ-Zone-Restore-Archive 在 CHANGELOG 里记录并专门隔离的 `334` 留言混入问题，我们在 1.2.36 又犯了一遍。

### 2. 互动摘要被当作说说正文
`QzoneApi.parsePavAll` 取 `div.txt-box` 的文本作为 `content`。历史卡片里这段文本常常是「某某评论了你的说说：……」这类互动描述，不是原文。
后果：检索结果里全是好友互动内容，被当成"本人历史说说"展示。这就是你小号测试看到的现象。

### 3. 作者归属未校验
- `parsePavAll` 的 `authorUin` 取自 mood 链接里的 uin，卡片没有该链接时为空串。
- `Ingest.commit` 不校验 `authorUin` 是否等于当前账号，也不区分"说说作者"与"互动发起者"。
- `Recovery.verdict` 只看 `body/time/inter`，只要有内容就升级成 RECOVERED_PARTIAL 或 FULL。
后果：别人的互动记录可以进入本人的"已恢复"列表，且状态看起来成功。

### 4. 事件与主体共用一套模型
`activities` 有 `actorUin/targetTid`，但 `MomentRec` 没有 `postAuthor` 与 `eventType` 字段，解析阶段就把两者混成一个对象，后面无法纠正。

### 5. 占位记录没有身份
`Ingest.kt` 第 150 行附近，互动指向的 tid 建占位时 `authorUin=""`、`content=""`，UI 只能显示 uin 数字或空，看起来像"上游未补充"。

## 三、接口存在性验证（真实请求，2026-11 沙箱）

未登录状态下的实际结果（不是推断）：

| 接口 | HTTP | 响应 | 结论 |
| --- | --- | --- | --- |
| feeds2_html_pav_all | 200 | `-3000 need login` | 地址与参数格式有效，需登录 |
| new/get_msgb | 200 | `-4001 未登录` | 地址有效，需登录 |
| feeds3_html_more | 200 | `-3000 need login` | 地址有效，需登录 |

能证明的：接口存在、参数被接受、鉴权失败。
不能证明的：登录后是否返回目标说说、是否返回已删除内容、分页是否有效。我没有任何登录态，不会把这三条当成"恢复能力已验证"。

## 四、外部项目核对（读源码与 CHANGELOG）

- QQ-Zone-Restore-Archive：CHANGELOG 明确记录 334 留言混入、历史点赞只是线索、get_feeds 被 WAF 拦 501、昵称锚点缺失导致写入 0 条、归属不明即停止写入。这些正是我们需要吸收的教训。
- QzoneTweetsBack：README 的做法是从网页「与我相关」复制真实请求，未证明接口对任意账号有效。offset 每次 +10 的去重方式可用，但同样缺归属判定。

## 五、需要你提供的最小证据（不给就不进阶段 4）

在小号上准备两条测试说说：
- A：删除前有好友点赞或评论（有互动残留）
- B：删除前无人互动

然后：
1. 电脑登录空间，打开「与我相关」，F12 → Network，翻 2–3 页。
2. 右键 `feeds2_html_pav_all` 请求 → Copy as cURL，或导出 HAR。
3. 同样导出 `get_msgb` 一条。
4. 关键字段：`offset`、`uin`、`hostUin`、`g_tk`、`qzonetoken`、响应里 `data[].html` 的片段（可脱敏，只保留结构）。

有了这份 HAR，才能确认真实响应里：
- 测试说说的 tid 是否出现；
- `txt-box` 到底是原文还是互动摘要；
- 是否能拿到 post_author。

## 六、阶段 4 的最小改造方案（未实施）

1. 新增解析层结构：`PostCandidate(postId, postAuthor, content, createdAt, evidenceSource)` 与 `EventCandidate(eventType, actor, targetPostId, content, eventTime)`，两者不再混用一个类型。
2. 归属判定：只有 `postAuthor == 当前 uin` 才允许进入 `moments`；否则只写 `activities` 并把对应 tid 标为 `INTERACTION_FRAGMENT`。
3. 留言独立表，不再进 `moments`。
4. `Recovery.verdict` 增加前提：归属未确认不得升级为 RECOVERED_*。
5. 数据库只加列/加表，不改旧列，迁移失败保留旧库。

先做 1–3，其余视 HAR 结果再定。
