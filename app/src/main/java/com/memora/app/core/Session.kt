package com.memora.app.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 会话：只在本地保存，用 Android Keystore 里的 AES-GCM 密钥加密。
 * 明文 Cookie 不落盘、不进日志、不进导出包。
 */
object SessionStore {

    private const val PREF = "memora_session"
    private const val KEY_ALIAS = "memora_session_key"
    private const val KEY_BLOB = "blob"
    private const val KEY_UIN = "uin"
    private const val KEY_NICK = "nickname"
    private const val KEY_SAVED_AT = "saved_at"

    fun save(context: Context, cookie: String, uin: String, nickname: String) {
        val sp = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val blob = runCatching { encrypt(cookie) }.getOrElse {
            Log.e("MemoraSession", "encrypt failed", it); ""
        }
        sp.edit()
            .putString(KEY_BLOB, blob)
            .putString(KEY_UIN, uin)
            .putString(KEY_NICK, nickname)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun cookie(context: Context): String {
        val sp = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val blob = sp.getString(KEY_BLOB, "") ?: ""
        if (blob.isBlank()) return ""
        return runCatching { decrypt(blob) }.getOrElse {
            Log.e("MemoraSession", "decrypt failed", it); ""
        }
    }

    fun uin(context: Context): String =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_UIN, "") ?: ""

    fun nickname(context: Context): String =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_NICK, "") ?: ""

    fun saveToken(context: Context, token: String) {
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("qzonetoken", token).apply()
    }

    fun token(context: Context): String =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("qzonetoken", "") ?: ""

    fun saveNickname(context: Context, nickname: String, avatar: String) {
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_NICK, nickname).putString("avatar", avatar).apply()
    }

    fun avatar(context: Context): String =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("avatar", "") ?: ""

    fun savedAt(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_SAVED_AT, 0)

    fun has(context: Context): Boolean = cookie(context).isNotBlank()

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** p_skey 是空间接口的 g_tk 来源，没有它所有请求都会被判未登录。 */
    fun pSkey(context: Context): String {
        val parts = cookie(context).split(";").map { it.trim() }
        fun pick(name: String) = parts.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.trim().orEmpty()
        return pick("p_skey").ifBlank { pick("skey") }
    }

    fun hasSpatialSession(context: Context): Boolean = cookie(context).isNotBlank() && pSkey(context).isNotBlank()

    /** skey 只在内存里用于计算 g_tk。 */
    fun skey(context: Context): String = cookie(context).split(";").map { it.trim() }
        .firstOrNull { it.startsWith("skey=") }?.substringAfter("skey=")?.trim().orEmpty()

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        if (raw.size < 13) return ""
        val iv = raw.copyOfRange(0, 12)
        val body = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    /** 从 Cookie 里取 QQ 号（uin=o0123... 或 p_uin=o0123...）。 */
    fun uinFromCookie(cookie: String): String {
        val candidates = listOf("uin", "p_uin", "ptui_loginuin", "luin")
        for (name in candidates) {
            val v = cookie.split(";").map { it.trim() }
                .firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.trim() ?: continue
            val digits = v.removePrefix("o").removePrefix("0")
            if (digits.all { it.isDigit() } && digits.length in 5..12) return digits
        }
        return ""
    }
}
