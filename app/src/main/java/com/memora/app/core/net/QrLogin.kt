package com.memora.app.core.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.URLEncoder

/**
 * 应用内扫码登录：ptqrshow 取二维码，ptqrlogin 轮询状态。
 * 全部在本机完成，不依赖 WebView 渲染，也不用用户复制 Cookie。
 * 平台若对当前网络返回 403，明确提示改用浏览器登录。
 */
object QrLogin {

    enum class State { WAITING, SCANNED, CONFIRMED, EXPIRED, BLOCKED, ERROR }

    data class Session(val image: Bitmap?, val qrsig: String, val token: String, val cookie: String)
    data class Poll(val state: State, val message: String, val redirect: String = "")

    fun hash33(s: String): String {
        var h = 5381
        for (ch in s) {
            h += (h shl 5) + ch.code
            h = h and 0x7FFFFFFF
        }
        return h.toString()
    }

    /** 第一步：登录票据 + 二维码（只请求一次，保证 qrsig 与图片对应）。 */
    fun start(): Session {
        var cookie = ""
        val landing = Net.get(
            "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=549000912&daid=5" +
                "&s_url=" + enc("https://qzone.qq.com/") + "&style=40",
            "", "https://qzone.qq.com/",
        )
        cookie = Net.mergeCookies(cookie, landing.setCookie)

        val qr = Net.getBinary(
            "https://ssl.ptlogin2.qq.com/ptqrshow?appid=549000912&e=2&l=M&s=3&d=72&v=4" +
                "&t=" + System.currentTimeMillis() + "&daid=5&pt_3rd_aid=0",
            cookie,
        )
        cookie = Net.mergeCookies(cookie, qr.setCookie)
        val qrsig = cookie.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("qrsig=") }?.substringAfter("=")?.trim().orEmpty()
        val bitmap = runCatching {
            val bytes = qr.bytes ?: return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
        return Session(bitmap, qrsig, hash33(qrsig), cookie)
    }

    /** 第二步：轮询。65 未扫码 / 66 已扫码待确认 / 67 过期 / 0 成功。 */
    fun poll(session: Session): Poll {
        if (session.qrsig.isBlank()) return Poll(State.ERROR, "二维码没有取到 请刷新")
        val loginSig = session.cookie.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("pt_login_sig=") }?.substringAfter("=")?.trim().orEmpty()
        val url = "https://ssl.ptlogin2.qq.com/ptqrlogin" +
            "?u1=" + enc("https://qzone.qq.com/") +
            "&ptqrtoken=" + session.token + "&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052" +
            "&action=0-0-" + System.currentTimeMillis() + "&js_ver=10233&js_type=1" +
            "&login_sig=" + enc(loginSig) + "&pt_uistyle=40&aid=549000912&daid=5&pt_3rd_aid=0"
        val res = Net.get(url, session.cookie, "https://xui.ptlogin2.qq.com/")
        if (res.code == 403) return Poll(State.BLOCKED, "当前网络被限制 请改用浏览器登录")
        if (!res.ok) return Poll(State.ERROR, res.error.ifBlank { "HTTP " + res.code })
        val body = res.body
        if (body.isBlank()) return Poll(State.ERROR, "没有返回状态")
        val code = Regex("ptuiCB\\('(\\d+)'").find(body)?.groupValues?.getOrNull(1).orEmpty()
        val fields = Regex("'([^']*)'").findAll(body).map { it.groupValues[1] }.toList()
        val message = fields.getOrNull(4).orEmpty().ifBlank { "等待扫码" }
        val redirect = fields.getOrNull(2).orEmpty()
        return when (code) {
            "0" -> Poll(State.CONFIRMED, message.ifBlank { "登录成功" }, redirect)
            "65" -> Poll(State.WAITING, "请用手机 QQ 扫码")
            "66" -> Poll(State.SCANNED, "已扫码 请在手机上确认")
            "67" -> Poll(State.EXPIRED, "二维码已失效")
            "1" -> Poll(State.WAITING, "已取消 可重新扫码")
            else -> Poll(State.WAITING, message)
        }
    }

    /** 第三步：跟随跳转，把空间会话（p_skey / uin）拿全。 */
    fun finish(session: Session, redirect: String): String {
        var cookie = session.cookie
        if (redirect.isNotBlank()) {
            val landing = Net.get(redirect, cookie, "https://qzone.qq.com/")
            cookie = Net.mergeCookies(cookie, landing.setCookie)
        }
        val uin = com.memora.app.core.SessionStore.uinFromCookie(cookie)
        if (uin.isNotBlank()) {
            val space = Net.get("https://user.qzone.qq.com/" + uin, cookie, "https://qzone.qq.com/")
            cookie = Net.mergeCookies(cookie, space.setCookie)
        }
        return cookie
    }

    private fun enc(s: String): String = runCatching { URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)
}
