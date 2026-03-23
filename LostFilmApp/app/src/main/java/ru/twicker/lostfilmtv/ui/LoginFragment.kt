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
        setStatus("Запуск ciadpi...")
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { CiadpiRunner.start(requireContext()) }
            if (!ok) { showError("ciadpi не запустился"); return@launch }

            val c = NetworkClient.createCiadpiClient(cookieJar).also { client = it }

            // Try existing session
            if (cookieJar.hasSession()) {
                setStatus("Проверка сессии...")
                val authed = withContext(Dispatchers.IO) { LostFilmAuth.isAuthenticated(c) }
                if (authed) {
                    setStatus("Загрузка эпизодов...")
                    val eps = withContext(Dispatchers.IO) {
                        try { repository.getRecentEpisodes(c) } catch (e: Exception) { null }
                    }
                    if (eps != null) {
                        Log.d(TAG, "Session valid, episodes loaded: ${eps.size}")
                        (activity as? ru.twicker.lostfilmtv.MainActivity)?.showBrowseFragment()
                        return@launch
                    }
                }
                Log.d(TAG, "Session expired, showing login")
                cookieJar.clearSession()
            }

            // Show login form
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
