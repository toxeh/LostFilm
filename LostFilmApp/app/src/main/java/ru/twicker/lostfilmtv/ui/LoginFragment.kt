package ru.twicker.lostfilmtv.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.twicker.lostfilmtv.R
import ru.twicker.lostfilmtv.network.*

/**
 * Login fragment — pure Android XML Views, no Compose.
 * Replaces Compose-based login screen to work correctly with Theme.Leanback.
 */
class LoginFragment : Fragment() {

    companion object {
        private const val TAG = "LoginFragment"
    }

    private val repository = LostFilmRepository()
    private var client: okhttp3.OkHttpClient? = null
    private lateinit var cookieJar: LostFilmAuth.PersistentCookieJar

    // Views
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var captchaLayout: View
    private lateinit var ivCaptcha: ImageView
    private lateinit var etCaptcha: EditText

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_login, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etEmail     = view.findViewById(R.id.et_email)
        etPassword  = view.findViewById(R.id.et_password)
        btnLogin    = view.findViewById(R.id.btn_login)
        tvError     = view.findViewById(R.id.tv_error)
        tvStatus    = view.findViewById(R.id.tv_status)
        progress    = view.findViewById(R.id.progress)
        captchaLayout = view.findViewById(R.id.captcha_layout)
        ivCaptcha   = view.findViewById(R.id.iv_captcha)
        etCaptcha   = view.findViewById(R.id.et_captcha)

        cookieJar = LostFilmAuth.cookieJar(requireContext())

        btnLogin.setOnClickListener { onLoginClicked() }

        // Pre-fill saved credentials
        val creds = LostFilmAuth.loadCredentials(requireContext())
        etEmail.setText(creds?.first ?: "")
        etPassword.setText(creds?.second ?: "")

        startupFlow()
    }

    private fun startupFlow() {
        lifecycleScope.launch {
            val ctx = requireContext()

            // ── Step 0: LAN discovery (before ciadpi — .205 may not have a strategy) ──
            val saved = CiadpiTuner.getSavedStrategy(ctx)
            if (saved == null && !cookieJar.hasSession()) {
                // No strategy AND no session → likely a fresh device, try LAN first
                setStatus("Поиск авторизованных устройств в сети...")
                val authPort = SettingsFragment.getAuthBroadcastPort(ctx)
                val payload = withContext(Dispatchers.IO) {
                    AuthBroadcast.discoverAndReceive(authPort, timeoutMs = 60_000)
                }
                if (payload != null) {
                    setStatus("Получены данные от другого устройства...")

                    // Import DPI strategy — try ciadpi, fallback to v6 client
                    var ciadpiStarted = false
                    if (payload.dpiTtl > 0 || payload.strategyIndex >= 0) {
                        CiadpiTuner.saveStrategy(ctx, payload.dpiTtl, payload.strategyIndex)
                        val strat = CiadpiTuner.getSavedStrategy(ctx)
                        if (strat != null) {
                            setStatus("Запуск ciadpi (${strat.name})...")
                            ciadpiStarted = withContext(Dispatchers.IO) {
                                CiadpiRunner.start(ctx, CiadpiTuner.getProductionArgs(strat))
                            }
                        }
                    }

                    // Import cookies and credentials
                    if (payload.cookies.isNotBlank()) {
                        LostFilmAuth.importCookies(ctx, payload.cookies)
                        if (payload.email.isNotBlank() && payload.password.isNotBlank()) {
                            LostFilmAuth.saveCredentials(ctx, payload.email, payload.password)
                            Log.d(TAG, "Saved credentials from LAN transfer")
                        }
                        val c = if (ciadpiStarted) {
                            NetworkClient.createSmartClient(cookieJar)
                        } else {
                            Log.d(TAG, "ciadpi unavailable, using v6 TLS record splitting client")
                            NetworkClient.createV6Client().newBuilder().cookieJar(cookieJar).build()
                        }
                        client = c
                        setStatus("Проверка авторизации...")
                        val authed = withContext(Dispatchers.IO) {
                            LostFilmAuth.isAuthenticated(c)
                        }
                        if (authed) {
                            Log.d(TAG, "LAN auth+strategy successful, proceeding to browse")
                            (activity as? ru.twicker.lostfilmtv.MainActivity)?.showBrowseFragment()
                            return@launch
                        }
                        Log.d(TAG, "LAN auth cookies invalid, continuing normal flow")
                    }
                }
            }

            // ── Step 0.5: Try direct access without ciadpi ──
            if (CiadpiTuner.getSavedStrategy(ctx) == null) {
                setStatus("Проверка прямого доступа...")
                val directOk = withContext(Dispatchers.IO) {
                    // Try system DNS first (wget on .205 works with system DNS → 188.114.97.1)
                    // Then DoH as fallback
                    for (dns in listOf<okhttp3.Dns>(okhttp3.Dns.SYSTEM, NetworkClient.doHDns)) {
                        try {
                            val dnsName = if (dns == okhttp3.Dns.SYSTEM) "system" else "DoH"
                            val directClient = okhttp3.OkHttpClient.Builder()
                                .dns(dns)
                                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            val resp = directClient.newCall(
                                okhttp3.Request.Builder()
                                    .url("https://www.lostfilm.tv/")
                                    .header("User-Agent", NetworkClient.UA)
                                    .build()
                            ).execute()
                            val body = resp.body?.string() ?: ""
                            resp.close()
                            val ok = resp.isSuccessful && body.length > 1000
                            Log.d(TAG, "Direct access ($dnsName): ${resp.code}, body=${body.length} → $ok")
                            if (ok) return@withContext true
                        } catch (e: Exception) {
                            Log.d(TAG, "Direct access failed: ${e.message}")
                        }
                    }
                    false
                }
                if (directOk) {
                    Log.i(TAG, "Site accessible without DPI bypass, skipping ciadpi")
                    // Use direct client (system DNS, no proxy) for everything
                    val directClient = okhttp3.OkHttpClient.Builder()
                        .cookieJar(cookieJar)
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .addInterceptor { chain ->
                            chain.proceed(chain.request().newBuilder()
                                .header("User-Agent", NetworkClient.UA).build())
                        }
                        .build()
                    client = directClient

                    // Check session or show login
                    if (cookieJar.hasSession()) {
                        setStatus("Проверка сессии...")
                        val authed = withContext(Dispatchers.IO) { LostFilmAuth.isAuthenticated(directClient) }
                        if (authed) {
                            Log.d(TAG, "Direct: session valid, proceeding to browse")
                            (activity as? ru.twicker.lostfilmtv.MainActivity)?.showBrowseFragment()
                            return@launch
                        }
                        cookieJar.clearSession()
                    }

                    setStatus("")
                    progress.visibility = View.GONE
                    withContext(Dispatchers.IO) { LostFilmAuth.initSession(directClient) }
                    loadAndShowCaptcha(directClient)
                    return@launch
                }
            }

            // ── Step 1: Start ciadpi with best strategy (or fallback / tune) ──
            val strategy = CiadpiTuner.getSavedStrategy(ctx)
            if (strategy != null) {
                setStatus("Запуск ciadpi (${strategy.name})...")
                val ok = withContext(Dispatchers.IO) { CiadpiRunner.start(ctx, CiadpiTuner.getProductionArgs(strategy)) }
                if (!ok) { showError("ciadpi не запустился"); return@launch }

                // Verify saved strategy with a test image download (340KB)
                setStatus("Проверка обхода DPI...")
                val verified = withContext(Dispatchers.IO) { CiadpiTuner.verifyStrategy() }
                if (!verified) {
                    setStatus("Стратегия устарела, подбираем заново...")
                    CiadpiTuner.clearSavedStrategy(ctx)
                    val bestIdx = CiadpiTuner.tune(ctx) { name, idx, total ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            setStatus("Подбор стратегии (${idx + 1}/$total): $name")
                        }
                    }
                    if (bestIdx < 0) { showError("Не удалось подобрать стратегию DPI"); return@launch }
                }
            } else if (CiadpiTuner.hasSavedStrategy(ctx)) {
                // Previous tuning found no winner → use tlsrec fallback (matches original behavior)
                val fallback = CiadpiStrategy("tlsrec fallback", listOf("--tlsrec", "1+s"))
                setStatus("Запуск обхода DPI...")
                val ok = withContext(Dispatchers.IO) { CiadpiRunner.start(ctx, CiadpiTuner.getProductionArgs(fallback)) }
                if (!ok) { showError("Не удалось запустить обход DPI"); return@launch }
                Log.d(TAG, "DPI bypass started (port=${CiadpiRunner.port})")
            } else {
                setStatus("Подбор стратегии DPI...")
                val bestIdx = CiadpiTuner.tune(ctx) { name, idx, total ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        setStatus("Подбор стратегии (${idx + 1}/$total): $name")
                    }
                }
                if (bestIdx < 0) { showError("Не удалось подобрать стратегию DPI"); return@launch }
            }

            val c = client ?: NetworkClient.createSmartClient(cookieJar).also { client = it }

            // ── Step 2: Check existing session ──
            if (cookieJar.hasSession()) {
                setStatus("Проверка сессии...")
                val authed = withContext(Dispatchers.IO) { LostFilmAuth.isAuthenticated(c) }
                if (authed) {
                    Log.d(TAG, "Session valid, proceeding to browse")
                    (activity as? ru.twicker.lostfilmtv.MainActivity)?.showBrowseFragment()
                    return@launch
                }
                Log.d(TAG, "Session expired, showing login")
                cookieJar.clearSession()
            }

            // ── Step 3: Show login form ──
            setStatus("")
            progress.visibility = View.GONE
            withContext(Dispatchers.IO) { LostFilmAuth.initSession(c) }
            loadAndShowCaptcha(c)
        }
    }

    private suspend fun loadAndShowCaptcha(c: okhttp3.OkHttpClient) {
        val bmp = withContext(Dispatchers.IO) { LostFilmAuth.fetchCaptchaBitmap(c) }
        if (bmp != null) {
            captchaLayout.visibility = View.VISIBLE
            ivCaptcha.setImageBitmap(bmp)
        }
    }

    private fun onLoginClicked() {
        val email   = etEmail.text.toString().trim()
        val pass    = etPassword.text.toString()
        val captcha = if (captchaLayout.visibility == View.VISIBLE) etCaptcha.text.toString() else null
        if (email.isBlank() || pass.isBlank()) {
            showError("Введите email и пароль")
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            doLogin(email, pass, captcha)
            setBusy(false)
        }
    }

    private suspend fun doLogin(email: String, pass: String, captcha: String?) {
        val c = client ?: return
        val result = withContext(Dispatchers.IO) {
            LostFilmAuth.login(requireContext(), c, email, pass, captcha)
        }
        when (result) {
            is LostFilmAuth.LoginResult.Success -> {
                setStatus("Загрузка эпизодов...")
                val eps = withContext(Dispatchers.IO) {
                    try { repository.getRecentEpisodes(c) } catch (e: Exception) { null }
                }
                if (eps != null) {
                    Log.d(TAG, "Login success, episodes: ${eps.size}")
                    (activity as? ru.twicker.lostfilmtv.MainActivity)?.showBrowseFragment()
                } else {
                    showError("Авторизован, но загрузка не удалась")
                }
            }
            is LostFilmAuth.LoginResult.NeedCaptcha -> {
                showError("Требуется капча")
                loadAndShowCaptcha(c)
            }
            is LostFilmAuth.LoginResult.Error -> {
                showError(result.message)
                loadAndShowCaptcha(c)
            }
        }
    }

    private fun setStatus(msg: String) {
        tvStatus.text = msg
        progress.visibility = if (msg.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = if (msg.isNotBlank()) View.VISIBLE else View.GONE
        tvStatus.text = ""
        progress.visibility = View.GONE
    }

    private fun setBusy(busy: Boolean) {
        btnLogin.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) tvError.visibility = View.GONE
    }
}
