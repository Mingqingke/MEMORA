package com.memora.app

import com.memora.app.core.Completeness
import com.memora.app.core.Confidence
import com.memora.app.core.Moment
import com.memora.app.core.RecoveryState
import com.memora.app.core.export.Exporter
import com.memora.app.core.export.Importer
import com.memora.app.core.net.Net
import com.memora.app.core.net.QzoneApi
import com.memora.app.core.recovery.RecoveryEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

    /* ---------------- 解析层 ---------------- */

    @Test
    fun jsonp_is_unwrapped() {
        val body = "_Callback({\"code\":0,\"data\":{\"total\":1}});"
        val json = Net.unwrapJsonp(body)
        assertNotNull(json)
        assertTrue(json!!.startsWith("{"))
        assertNull(Net.unwrapJsonp("not a jsonp"))
        assertEquals("{\"a\":1}", Net.unwrapJsonp("{\"a\":1}"))
    }

    @Test
    fun gtk_matches_hash33() {
        // hash33("") 会返回 0，用于没有 skey 的会话
        assertEquals("0", Net.gtk(""))
        val v = Net.gtk("abc")
        assertTrue(v.toLong() > 0)
        assertEquals(v, Net.gtk("abc"))
    }

    @Test
    fun time_accepts_seconds_millis_and_dates() {
        assertEquals(1_700_000_000_000L, QzoneApi.time(1_700_000_000))
        assertEquals(1_700_000_000_000L, QzoneApi.time(1_700_000_000_000L))
        assertEquals(1_700_000_000_000L, QzoneApi.time("1700000000"))
        assertNull(QzoneApi.time(null))
        assertNull(QzoneApi.time(0))
        assertNull(QzoneApi.time(""))
        // 未知时间必须是 null，绝不能变成 1970
        val parsed = QzoneApi.time("在很久以前")
        assertNull(parsed)
    }

    @Test
    fun friends_parse_including_groups() {
        val body = """
            _Callback({"code":0,"data":{"items_list":[
              {"fuin":10001,"name":"林","remark":"老林","img":"http://a/1.jpg","groupid":1},
              {"fuin":10002,"name":"周","remark":"","img":"http://a/2.jpg","groupid":0}
            ],"gpname_list":[{"gpid":1,"gpname":"同学"}],"total":2}});
        """.trimIndent()
        val result = QzoneApi.parseFriends(body)
        assertEquals(com.memora.app.core.SourceStatus.AVAILABLE, result.status)
        assertEquals(2, result.items.size)
        assertEquals("10001", result.items[0].uin)
        assertEquals("老林", result.items[0].remark)
        assertEquals("同学", result.items[0].groups)
        assertEquals("", result.items[1].groups)
    }

    @Test
    fun friends_parse_reports_parse_error_instead_of_empty() {
        val result = QzoneApi.parseFriends("<html>login</html>")
        assertEquals(com.memora.app.core.SourceStatus.PARSE_ERROR, result.status)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun msg_list_parses_body_comments_and_media() {
        val body = """
            _preloadCallback({"code":0,"hasmore":1,"msglist":[
              {"tid":"abc123456","uin":10001,"content":"今天下雨了","created_time":1700000000,
               "lbs":{"name":"西安"},"secret":0,
               "pic":[{"url1":"http://p/1.jpg","url2":"http://p/2.jpg"}],
               "like":[{"uin":10002,"name":"周"}],
               "comments":[
                 {"commentid":"c1","uin":10002,"name":"周","content":"记得带伞","create_time":1700000100},
                 {"commentid":"c2","uin":10003,"name":"林","content":"同感","create_time":1700000200,
                  "list_3":[{"commentid":"c3","uin":10001,"name":"我","content":"谢谢","create_time":1700000300}]}
               ]}
            ]});
        """.trimIndent()
        val result = QzoneApi.parseMsgList(body, 0, 20)
        assertEquals(com.memora.app.core.SourceStatus.AVAILABLE, result.status)
        assertEquals(1, result.items.size)
        val m = result.items[0]
        assertEquals("今天下雨了", m.content)
        assertEquals(1_700_000_000_000L, m.createTime)
        assertEquals("西安", m.location)
        assertEquals(2, m.pics.size)
        assertEquals(3, m.comments.size)
        assertEquals("c1", m.comments[0].id)
        assertEquals("c2", m.comments[2].parentId)
        assertEquals(1, m.likes.size)
        assertTrue(result.hasMore)
    }

    @Test
    fun feed_stream_parses_embedded_json() {
        val body = """
            _Callback({"code":0,"data":[
              {"type":1,"abstime":1700000000,
               "data":"{\"tid\":\"9f8e7d6c\",\"content\":\"旧的一条说说\",\"uin\":10001}"},
              {"type":1,"abstime":1690000000,
               "data":"{\"tid\":\"8e7d6c5b\",\"content\":\"更早的一条\",\"uin\":10001}"}
            ]});
        """.trimIndent()
        val result = QzoneApi.parseFeedStream(body, 1, 0, 20)
        assertEquals(com.memora.app.core.SourceStatus.AVAILABLE, result.status)
        assertEquals(2, result.items.size)
        assertEquals("9f8e7d6c", result.items[0].tid)
        assertEquals("旧的一条说说", result.items[0].content)
    }

    @Test
    fun feed_stream_distinguishes_empty_from_parse_error() {
        val empty = QzoneApi.parseFeedStream("_Callback({\"data\":[]});", 2, 0, 20)
        assertEquals(com.memora.app.core.SourceStatus.EMPTY, empty.status)
        val broken = QzoneApi.parseFeedStream("_Callback({\"nope\":1});", 2, 0, 20)
        assertEquals(com.memora.app.core.SourceStatus.PARSE_ERROR, broken.status)
    }

    @Test
    fun feeds_html_extracts_cards() {
        val html = """
            <html><body>
              <ul>
                <li class="feed" data-tid="1122334455">
                  <div class="f-info">去年今天写的一段话</div>
                  <div class="f-time" title="2019-05-01 12:00">2019</div>
                  <img src="http://p/x.jpg"/>
                </li>
              </ul>
            </body></html>
        """.trimIndent()
        val result = QzoneApi.parseFeedsHtml(html)
        assertEquals(com.memora.app.core.SourceStatus.AVAILABLE, result.status)
        assertEquals(1, result.items.size)
        assertEquals("1122334455", result.items[0].tid)
        assertEquals("去年今天写的一段话", result.items[0].content)
        assertNotNull(result.items[0].createTime)
        assertEquals(1, result.items[0].pics.size)
    }

    /* ---------------- 恢复判定 ---------------- */

    private fun moment(content: String, time: Long?, state: RecoveryState = RecoveryState.UNKNOWN) = Moment(
        id = "qq:1:x", accountId = "qq:1", tid = "x", authorUin = "1", content = content,
        createTime = time, location = "", state = state, completeness = Completeness.CLUE,
        confidence = Confidence.LOW, fieldCount = 0, createdAt = 0, updatedAt = 0,
        commentCount = 0, activityCount = 0, mediaCount = 0,
    )

    @Test
    fun current_source_is_never_reported_as_recovered() {
        val v = RecoveryEngine.verdict(
            moment("还在的内容", 1_700_000_000_000L),
            setOf("body", "time", "comments"), comments = 2, activities = 1, sources = setOf("current", "interaction"),
        )
        assertEquals(RecoveryState.CURRENT, v.state)
        assertEquals(Completeness.FULL, v.completeness)
    }

    @Test
    fun body_plus_time_plus_interaction_is_full_recovery() {
        val v = RecoveryEngine.verdict(
            moment("找回的正文", 1_700_000_000_000L),
            setOf("body", "time", "comments"), comments = 3, activities = 0,
            sources = setOf("interaction", "legacy"),
        )
        assertEquals(RecoveryState.RECOVERED_FULL, v.state)
        assertEquals(Confidence.MEDIUM, v.confidence)
    }

    @Test
    fun body_without_time_is_partial() {
        val v = RecoveryEngine.verdict(
            moment("只有正文", null), setOf("body"), comments = 0, activities = 0, sources = setOf("legacy"),
        )
        assertEquals(RecoveryState.RECOVERED_PARTIAL, v.state)
        assertEquals(Completeness.PARTIAL, v.completeness)
        assertTrue(v.missing.any { it.field == "发布时间" })
    }

    @Test
    fun interaction_only_single_source_is_fragment() {
        val v = RecoveryEngine.verdict(
            moment("", null), setOf("like"), comments = 0, activities = 1, sources = setOf("interaction"),
        )
        assertEquals(RecoveryState.INTERACTION_FRAGMENT, v.state)
        assertEquals(Completeness.CLUE, v.completeness)
    }

    @Test
    fun two_independent_fragments_are_inferred() {
        val v = RecoveryEngine.verdict(
            moment("", null), setOf("comments", "like"), comments = 2, activities = 1,
            sources = setOf("interaction", "local"),
        )
        assertEquals(RecoveryState.INFERRED, v.state)
    }

    @Test
    fun no_body_and_no_interaction_is_not_full() {
        val v = RecoveryEngine.verdict(moment("", null), emptySet(), 0, 0, setOf("legacy"))
        assertEquals(RecoveryState.UNAVAILABLE, v.state)
        assertEquals(Completeness.CLUE, v.completeness)
    }

    /* ---------------- 导入 ---------------- */

    @Test
    fun importer_reads_memora_export() {
        val json = """
            {"format":"memora-archive","formatVersion":1,"moments":[
              {"tid":"123456789","content":"导出的内容","createTime":1700000000000,
               "comments":[{"id":"c1","authorName":"周","content":"好的"}]}
            ],"friends":[{"uin":"10001","nickname":"林","remark":"老林","groups":"同学"}]}
        """.trimIndent()
        val p = Importer.parse(json, "MEMORA.zip")
        assertTrue(p.valid)
        assertEquals(1, p.moments.size)
        assertEquals(1, p.friends.size)
        assertEquals("导出的内容", p.moments[0].content)
        assertEquals(1, p.moments[0].comments.size)
    }

    @Test
    fun importer_rejects_unknown_structure() {
        val p = Importer.parse("{\"hello\":\"world\"}", "x.json")
        assertFalse(p.valid)
    }

    @Test
    fun importer_skips_records_without_valid_id() {
        val json = """[{"tid":"abc","content":"没有数字 ID"},{"tid":"99887766","content":"正常"}]"""
        val p = Importer.parse(json, "arr.json")
        assertTrue(p.valid)
        assertEquals(1, p.moments.size)
        assertTrue(p.warnings.isNotEmpty())
    }

    @Test
    fun sha256_is_stable() {
        val a = Exporter.sha256("MEMORA".toByteArray())
        val b = Exporter.sha256("MEMORA".toByteArray())
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a != Exporter.sha256("memora".toByteArray()))
    }

    /* -------- 历史互动流：真实结构多样性 -------- */

    @Test
    fun feed_stream_handles_object_wrapped_list() {
        val body = """
            _Callback({"code":0,"subcode":0,"message":"","default":0,
              "data":{"main":{"feedlist":[
                {"tid":"7788990011","content":"被删掉的一条","abstime":1690000000,"uin":10001}
              ]}}});
        """.trimIndent()
        val r = QzoneApi.parseFeedStream(body, 1, 0, 20)
        assertEquals(com.memora.app.core.SourceStatus.AVAILABLE, r.status)
        assertEquals(1, r.items.size)
        assertEquals("7788990011", r.items[0].tid)
        assertEquals("被删掉的一条", r.items[0].content)
    }

    @Test
    fun feed_stream_handles_html_chunk_inside_json() {
        val html = """<li class="feed" data-tid="6655443322"><div class="f-info">旧版残留</div></li>"""
        val body = """_Callback({"code":0,"data":{"main":{"html":${"'" + "$"}html${"'" + "$"}}}});"""
            .replace("${"'" + "$"}html${"'" + "$"}", org.json.JSONObject.quote(html))
        val r = QzoneApi.parseFeedStream(body, 2, 0, 20)
        assertEquals(com.memora.app.core.SourceStatus.AVAILABLE, r.status)
        assertEquals("6655443322", r.items[0].tid)
    }

    @Test
    fun feed_stream_falls_back_to_body_pairs() {
        val body = """_Callback({"code":0,"data":{"main":{"x":{"tid":"5544332211","content":"兜底解析到的正文"}}}});"""
        val r = QzoneApi.parseFeedStream(body, 1, 0, 20)
        assertEquals(com.memora.app.core.SourceStatus.AVAILABLE, r.status)
        assertEquals("5544332211", r.items[0].tid)
        assertEquals("兜底解析到的正文", r.items[0].content)
    }

    @Test
    fun msg_list_reads_video_entries() {
        val body = """
            _preloadCallback({"code":0,"msglist":[
              {"tid":"9988776655","uin":10001,"content":"带视频","created_time":1700000000,
               "video":[{"url1":"http://v/cover.jpg","url3":"http://v/clip.mp4"}]}
            ]});
        """.trimIndent()
        val r = QzoneApi.parseMsgList(body, 0, 20)
        assertEquals(1, r.items.size)
        assertTrue(r.items[0].videos.any { it.contains(".mp4") })
    }

    /* -------- mood id：历史残留的 tid 与发布时间 -------- */

    @Test
    fun mood_cell_id_strips_event_suffix() {
        assertEquals("abc12345.1", com.memora.app.core.net.QzoneApi.moodCellId(
            "<a href=\"//user.qzone.qq.com/10001/mood/abc12345.1\">x</a>"
        ))
        assertEquals("abc12345", com.memora.app.core.net.QzoneApi.baseTid("abc12345.1"))
        assertEquals("abc12345", com.memora.app.core.net.QzoneApi.baseTid("abc12345."))
        assertEquals("abc12345", com.memora.app.core.net.QzoneApi.baseTid("abc12345"))
    }

    @Test
    fun mood_published_time_decodes_little_endian_hex() {
        // uin=10001 -> 0x2711；时间 1600000000 = 0x5F5E1000
        fun le(v: Long): String {
            val b = ByteArray(4)
            for (i in 0 until 4) b[i] = ((v shr (8 * i)) and 0xFF).toByte()
            return b.joinToString("") { "%02x".format(it) }
        }
        val cell = le(10001) + le(1_600_000_000L) + "abcd"
        val t = com.memora.app.core.net.QzoneApi.moodPublishedAt(cell)
        assertEquals(1_600_000_000_000L, t)
        assertNull(com.memora.app.core.net.QzoneApi.moodPublishedAt("zz"))
    }

    /* -------- 与我相关：从 HTML 卡片找回已删除说说 -------- */

    @Test
    fun pav_all_reads_deleted_mood_from_card() {
        val html = """<div class="feed"><div class="txt-box">2022 年的旧说说正文</div>""" +
            """<div class="mod-comments">张三: 好怀念</div>""" +
            """<a href="//user.qzone.qq.com/10001/mood/0a1b2c3d.1">x</a></div>"""
        val body = "_Callback(" + org.json.JSONObject().apply {
            put("code", 0)
            put("data", org.json.JSONArray().put(org.json.JSONObject().apply {
                put("uin", "10001")
                put("nickname", "青")
                put("feedstime", 1_650_000_000)
                put("html", html)
            }))
        }.toString() + ");"
        val r = com.memora.app.core.net.QzoneApi.parsePavAll(body, 0, 10)
        assertEquals(com.memora.app.core.SourceStatus.AVAILABLE, r.status)
        assertEquals(1, r.items.size)
        assertEquals("0a1b2c3d", r.items[0].tid)
        assertTrue(r.items[0].content.contains("2022"))
    }

    /* -------- 线索不得冒充正文 -------- */

    @Test
    fun pav_all_marks_records_as_clue() {
        val html = """<div class="txt-box">张三评论了你的说说</div><a href="//user.qzone.qq.com/10001/mood/0a1b2c3d.1">x</a>"""
        val body = "_Callback(" + org.json.JSONObject().apply {
            put("code", 0)
            put("data", org.json.JSONArray().put(org.json.JSONObject().apply {
                put("uin", "10001"); put("feedstime", 1_650_000_000); put("html", html)
            }))
        }.toString() + ");"
        val r = com.memora.app.core.net.QzoneApi.parsePavAll(body, 0, 10)
        assertEquals("clue", r.items[0].kind)
    }

    @Test
    fun guestbook_marks_records_as_clue() {
        val body = """{"code":0,"data":{"commentList":[{"commentId":"9","uin":"10002","content":"留言内容","datetime":"2022-05-01 10:00:00"}]}}"""
        val r = com.memora.app.core.net.QzoneApi.parseGuestbook(body, 0, 20)
        assertEquals("clue", r.items[0].kind)
        assertTrue(r.items[0].tid.startsWith("gb:"))
    }
}
