package ru.twicker.lostfilmtv.network

import android.content.Context
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "LostFilmAuth"
private const val PREFS = "lostfilm_auth"

/**
 * Handles lostfilm.tv authentication.
 *
 * Login endpoint: POST https://www.lostfilm.tv/ajaxik.users.php
 * Fields: act=users, type=login, mail=<email>, pass=<password>, rem=1
 *
 * On success response: {"success":1,...} + Set-Cookie session cookies.
 * Cookies are persisted to SharedPreferences and reused across app restarts.
 */
object LostFilmAuth {

    /**
     * A CookieJar that stores cookies in memory and persists them to SharedPreferences.
     */
    class PersistentCookieJar(private val context: Context) : CookieJar {
        private val cookies = CopyOnWriteArrayList<Cookie>()

        init { load() }

        override fun saveFromResponse(url: HttpUrl, responseCookies: List<Cookie>) {
            val newUrls = responseCookies.map { it.name }.toSet()
            cookies.removeAll { it.name in newUrls }
            cookies.addAll(responseCookies)
            persist()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookies.filter { it.matches(url) }

        fun hasSession(): Boolean = cookies.any { it.name == "lf_session" || it.name == "PHPSESSID" }

        fun clear() { cookies.clear(); persist() }

        private fun persist() {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val encoded = cookies.joinToString("|") { c ->
                "${c.name}=${c.value};domain=${c.domain};path=${c.path}"
            }
            prefs.edit().putString("cookies", encoded).apply()
        }

        private fun load() {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val encoded = prefs.getString("cookies", "") ?: return
            if (encoded.isBlank()) return
            for (part in encoded.split("|")) {
                try {
                    val kv = part.split(";")
                    val (name, value) = kv[0].split("=", limit = 2)
                    val domain = kv.firstOrNull { it.startsWith("domain=") }?.removePrefix("domain=") ?: ".lostfilm.tv"
                    val path   = kv.firstOrNull { it.startsWith("path=")   }?.removePrefix("path=")   ?: "/"
                    val cookie = Cookie.Builder()
                        .name(name).value(value)
                        .domain(domain.trimStart('.')).path(path)
                        .build()
                    cookies.add(cookie)
                } catch (_: Exception) {}
            }
            Log.d(TAG, "Loaded ${cookies.size} cookies from prefs")
        }
    }

    // Singleton cookie jar — shared by all OkHttp clients so cookies are used everywhere
    private var cookieJar: PersistentCookieJar? = null

    fun cookieJar(context: Context): PersistentCookieJar {
        if (cookieJar == null) cookieJar = PersistentCookieJar(context.applicationContext)
        return cookieJar!!
    }

    fun hasSession(context: Context) = cookieJar(context).hasSession()

    /** Save credentials to SharedPreferences (plain text — acceptable for media app) */
    fun saveCredentials(context: Context, email: String, password: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("email", email)
            .putString("pass", password)
            .apply()
    }

    fun loadCredentials(context: Context): Pair<String, String>? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val email = p.getString("email", "") ?: ""
        val pass  = p.getString("pass",  "") ?: ""
        return if (email.isNotBlank() && pass.isNotBlank()) Pair(email, pass) else null
    }

    fun logout(context: Context) {
        cookieJar(context).clear()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("cookies").apply()
    }

    sealed class LoginResult {
        object Success : LoginResult()
        data class NeedCaptcha(val captchaImageUrl: String) : LoginResult()
        data class Error(val message: String) : LoginResult()
    }

    /**
     * GET /login/ to establish PHPSESSID cookie.
     * Captcha image and login POST must share this session.
     */
    fun initSession(client: OkHttpClient) {
        try {
            val req = Request.Builder()
                .url("https://www.lostfilm.tv/login/")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val resp = client.newCall(req).execute()
            resp.body?.close()
            Log.d(TAG, "initSession: ${resp.code}, cookies=${client.cookieJar.loadForRequest("https://www.lostfilm.tv/".toHttpUrl()).map { "${it.name}=${it.value.take(10)}" }}")
        } catch (e: Exception) {
            Log.e(TAG, "initSession failed: ${e.message}")
        }
    }

    /**
     * Fetch CAPTCHA image as Bitmap via OkHttp (same cookies as login).
     * Must be called on IO dispatcher.
     */
    fun fetchCaptchaBitmap(client: OkHttpClient): android.graphics.Bitmap? {
        return try {
            val url = "https://www.lostfilm.tv/simple_captcha.php?${System.currentTimeMillis()}"
            val req = Request.Builder()
                .url(url)
                .header("Referer", "https://www.lostfilm.tv/login/")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val resp = client.newCall(req).execute()
            val bytes = resp.body?.bytes()
            Log.d(TAG, "fetchCaptcha: ${resp.code}, ${bytes?.size ?: 0} bytes")
            if (bytes != null && bytes.isNotEmpty()) {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "fetchCaptcha failed: ${e.message}")
            null
        }
    }

    /**
     * Perform login. Always sends need_captcha and captcha fields.
     * Must be called on IO dispatcher.
     */
    fun login(
        context: Context,
        client: OkHttpClient,
        email: String,
        password: String,
        captcha: String? = null
    ): LoginResult {
        return try {
            val bodyBuilder = FormBody.Builder()
                .add("act", "users")
                .add("type", "login")
                .add("mail", email)
                .add("pass", password)
                .add("rem", "1")
                .add("need_captcha", if (captcha != null) "1" else "")
                .add("captcha", captcha ?: "")

            val request = Request.Builder()
                .url("https://www.lostfilm.tv/ajaxik.users.php")
                .post(bodyBuilder.build())
                .header("Referer", "https://www.lostfilm.tv/login/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val response = client.newCall(request).execute()
            val text = response.body?.string() ?: ""
            Log.d(TAG, "Login response ${response.code}: $text")

            val json = JSONObject(text)
            when {
                json.optBoolean("success") || json.optInt("success") == 1 -> {
                    saveCredentials(context, email, password)
                    Log.i(TAG, "Login successful")
                    LoginResult.Success
                }
                json.optBoolean("need_captcha") -> {
                    val captchaUrl = "https://www.lostfilm.tv/simple_captcha.php?${System.currentTimeMillis()}"
                    Log.d(TAG, "CAPTCHA required → $captchaUrl")
                    LoginResult.NeedCaptcha(captchaUrl)
                }
                json.has("error") -> {
                    val errVal = json.get("error")
                    val msg = when (errVal) {
                        is String -> errVal
                        is Number -> if (errVal.toInt() == 1) "Неверный логин/пароль или каптча" else "Ошибка #$errVal"
                        else -> errVal.toString()
                    }
                    LoginResult.Error(msg)
                }
                json.has("notice") -> LoginResult.Error(json.optString("notice", "Ошибка"))
                else -> LoginResult.Error("Неизвестная ошибка: $text")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login failed: ${e.message}", e)
            LoginResult.Error("Ошибка сети: ${e.message}")
        }
    }
}
