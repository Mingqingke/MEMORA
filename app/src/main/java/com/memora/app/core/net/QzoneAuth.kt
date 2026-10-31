package com.memora.app.core.net

import android.content.Context
import android.util.Log
import com.memora.app.core.SessionStore
import com.memora.app.core.SourceStatus
import org.json.JSONObject
import java.net.URLEncoder

/**
 * QQ 空间鉴权。实测结论（2026-10，未登录请求）：
 * - msglist_v6 / feeds3_html_more / fcg_list_album_v3 / friend_ship_manager.cgi
 *   未登录一律返回 code=-3000「请先登录空间」，说明路径有效，问题只在会话。
 * - g_tk 必须用 p_skey 计算（用 skey 会全部失败）。
 * - 接口必须带 qzonetoken，该值只出现在 user.qzone.qq.com/{uin} 的 HTML 里。
 */
object QzoneAuth {

    data class Session(
        val uin: String,
        val cookie: String,
        val pSkey: String,
        val gtk: String,
        val qzonetoken: String,
        val nickname: String,
        val avatar: String,
    ) {
        val ready: Boolean get() = uin.isNotBlank() && cookie.isNotBlank() && pSkey.isNotBlank()
    }

    data class Bootstrap(
        val session: Session,
        val status: SourceStatus,
        val detail: String,
    )

    fun pSkey(cookie: String): String {
        val parts = cookie.split(";").map { it.trim() }
        fun pick(name: String) = parts.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.trim().orEmpty()
        return pick("p_skey").ifBlank { pick("skey") }.ifBlank { pick("p_skey2") }
    }

    /** 与网页一致的 hash33。 */
    fun gtk(skey: String): String = Net.gtk(skey)

    fun cookieValue(cookie: String, name: String): String =
        cookie.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.trim().orEmpty()

    /**
     * 建立会话：访问空间首页拿 qzonetoken 与昵称，然后校验一个真实接口。
     * 任何一步失败都返回可读原因，而不是让后续任务默默全挂。
     */
    suspend fun bootstrap(context: Context, uin: String, rawCookie: String): Bootstrap {
        if (uin.isBlank() || rawCookie.isBlank()) {
            return Bootstrap(
                Session(uin, rawCookie, "", "0", "", "", ""),
                SourceStatus.AUTH_REQUIRED,
                "Cookie 缺少 uin",
            )
        }
        // WebView 只保证有 skey；p_skey 要访问一次空间首页才会下发，这里先取并回写
        var cookie = rawCookie
        if (pSkey(cookie).isBlank()) {
            val landing = Net.get("https://user.qzone.qq.com/$uin", cookie, "https://qzone.qq.com/")
            cookie = Net.mergeCookies(cookie, landing.setCookie)
        }
        var pskey = pSkey(cookie)
        if (pskey.isBlank()) {
            return Bootstrap(
                Session(uin, cookie, "", "0", "", "", ""),
                SourceStatus.AUTH_REQUIRED,
                "没有取得 p_skey · 请重新登录",
            )
        }
        var gtk = gtk(pskey)
        var token = ""
        var nickname = ""
        var avatar = ""

        val page = Net.get("https://user.qzone.qq.com/$uin", cookie, "https://qzone.qq.com/")
        cookie = Net.mergeCookies(cookie, page.setCookie)
        pskey = pSkey(cookie)
        if (pskey.isBlank()) {
            return Bootstrap(
                Session(uin, cookie, "", "0", "", "", ""),
                SourceStatus.AUTH_REQUIRED,
                "会话里没有 p_skey · 需要在浏览器里打开一次 QQ 空间",
            )
        }
        gtk = gtk(pskey)
        if (page.ok) {
            token = Regex("window\\.g_qzonetoken\\s*=\\s*\"([^\"]+)\"").find(page.body)?.groupValues?.getOrNull(1)
                ?: Regex("qzonetoken\\s*=\\s*\"([^\"]+)\"").find(page.body)?.groupValues?.getOrNull(1)
                ?: ""
            nickname = Regex("window\\.g_nickname\\s*=\\s*\"([^\"]+)\"").find(page.body)?.groupValues?.getOrNull(1) ?: ""
            avatar = Regex("\"avatar\"\\s*:\\s*\"([^\"]+)\"").find(page.body)?.groupValues?.getOrNull(1) ?: ""
        } else if (page.code == 0) {
            return Bootstrap(
                Session(uin, cookie, pskey, gtk, "", "", ""),
                SourceStatus.SERVER_ERROR,
                "无法连接 QQ 空间 · ${page.error}",
            )
        }

        // 首页拿不到就退回资料接口，至少确认会话是否有效
        val info = userInfo(QzoneContext(context, "", uin, cookie, gtk, token))
        cookie = Net.mergeCookies(cookie, info.third)
        if (nickname.isBlank()) nickname = info.second.first
        if (avatar.isBlank()) avatar = info.second.second
        if (token.isBlank()) token = info.second.third

        val probe = Net.get(
            msgListUrl(uin, gtk, token, 0, 1), cookie, "https://user.qzone.qq.com/$uin",
        )
        val root = Net.json(probe.body)
        val status = when {
            probe.code == 0 -> SourceStatus.SERVER_ERROR
            root == null -> SourceStatus.PARSE_ERROR
            root.optInt("code", 0) == 0 -> SourceStatus.AVAILABLE
            root.optInt("code", 0) == -3000 -> SourceStatus.AUTH_REQUIRED
            else -> SourceStatus.AUTH_REQUIRED
        }
        val detail = when (status) {
            SourceStatus.AVAILABLE -> "会话可用" + if (token.isNotBlank()) " · 已取得 qzonetoken" else ""
            SourceStatus.AUTH_REQUIRED -> "空间会话未建立 · " + (root?.optString("message") ?: "需要重新登录")
            SourceStatus.SERVER_ERROR -> "连接失败 · ${probe.error.ifBlank { "HTTP ${probe.code}" }}"
            else -> "响应无法解析 · ${probe.body.take(80)}"
        }
        if (status == SourceStatus.AVAILABLE) {
            SessionStore.saveToken(context, token)
            if (nickname.isNotBlank()) SessionStore.saveNickname(context, nickname, avatar)
        }
        return Bootstrap(Session(uin, cookie, pskey, gtk, token, nickname, avatar), status, detail)
    }

    /** 本人资料：昵称 / 头像 / qzonetoken。失败返回空值，不抛异常。 */
    fun userInfo(ctx: QzoneContext): Triple<Int, Triple<String, String, String>, List<String>> {
        val url = "https://user.qzone.qq.com/proxy/domain/base.qzone.qq.com/cgi-bin/user/cgi_userinfo_get_all" +
            "?uin=${ctx.uin}&vuin=${ctx.uin}&fupdate=1&g_tk=${ctx.gtk}" +
            (if (ctx.qzonetoken.isBlank()) "" else "&qzonetoken=" + encode(ctx.qzonetoken)) + "&format=json"
        val res = Net.get(url, ctx.cookie, "https://user.qzone.qq.com/${ctx.uin}")
        val root = Net.json(res.body) ?: return Triple(res.code, Triple("", "", ""), res.setCookie)
        val data = root.optJSONObject("data") ?: root
        val nick = data.optString("nickname", "").ifBlank { data.optString("name", "") }
        val face = listOf("avatar", "portrait", "img", "face", "headimgurl")
            .firstNotNullOfOrNull { k -> data.optString(k, "").takeIf { it.isNotBlank() } } ?: ""
        return Triple(root.optInt("code", -1), Triple(nick, face, ctx.qzonetoken), res.setCookie)
    }

    fun msgListUrl(uin: String, gtk: String, token: String, pos: Int, num: Int): String =
        "https://user.qzone.qq.com/proxy/domain/taotao.qq.com/cgi-bin/emotion_cgi_msglist_v6" +
            "?uin=$uin&ftype=0&sort=0&pos=$pos&num=$num&replynum=50&g_tk=$gtk" +
            (if (token.isNotBlank()) "&qzonetoken=" + URLEncoder.encode(token, "UTF-8") else "") +
            "&callback=_preloadCallback&code_version=1&format=jsonp&need_private_comment=1"

    fun encode(s: String): String = runCatching { URLEncoder.encode(s, "UTF-8") }.getOrDefault("")
}
