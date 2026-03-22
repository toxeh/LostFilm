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

    /**
     * Perform login. Returns null on success, error message on failure.
     * Must be called on IO dispatcher.
     */
    fun login(context: Context, client: OkHttpClient, email: String, password: String): String? {
        return try {
            val body = FormBody.Builder()
                .add("act", "users")
                .add("type", "login")
                .add("mail", email)
                .add("pass", password)
                .add("rem", "1")       // remember me
                .build()

            val request = Request.Builder()
                .url("https://www.lostfilm.tv/ajaxik.users.php")
                .post(body)
                .header("Referer", "https://www.lostfilm.tv/login/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val response = client.newCall(request).execute()
            val text = response.body?.string() ?: ""
            Log.d(TAG, "Login response ${response.code}: $text")

            val json = JSONObject(text)
            when {
                json.optInt("success") == 1 -> {
                    saveCredentials(context, email, password)
                    Log.i(TAG, "Login successful")
                    null // success
                }
                json.has("error") -> json.getString("error")
                json.has("notice") -> json.getString("notice")
                else -> "Неизвестная ошибка: $text"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login failed: ${e.message}", e)
            "Ошибка сети: ${e.message}"
        }
    }
}
