package ru.twicker.lostfilmtv

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.twicker.lostfilmtv.network.CiadpiRunner
import ru.twicker.lostfilmtv.network.Episode
import ru.twicker.lostfilmtv.network.LostFilmAuth
import ru.twicker.lostfilmtv.network.LostFilmRepository
import ru.twicker.lostfilmtv.network.NetworkClient
import ru.twicker.lostfilmtv.network.TorrentEntry
import ru.twicker.lostfilmtv.network.TorrentsResult

private const val TAG = "MainActivity"

// ─── Original LostFilm colors ─────────────────────────────────────────────────
private val LF_Primary      = Color(0xFF222538)   // colorPrimary from decompiled
private val LF_PrimaryDark  = Color(0xFF14181C)   // colorPrimaryDark (window bg)
private val LF_Surface      = Color(0xFF1A1D2E)   // slightly lighter than primary
private val LF_SurfaceVar   = Color(0xFF2A2D42)   // card backgrounds
private val LF_Red          = Color(0xFFC52400)   // LostFilm red accent
private val LF_RedLight     = Color(0xFFE53935)   // lighter red for text/icons
private val LF_TextPrimary  = Color(0xFFEEEEEE)   // lb_browse_title_color
private val LF_TextSecondary= Color(0xB3EEEEEE)   // lb_details_description_body
private val LF_OnPrimary    = Color.White

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private val repository = LostFilmRepository()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LostFilmTheme {
                Surface(Modifier.fillMaxSize(), color = LF_PrimaryDark) {
                    MainScreen(repository)
                }
            }
        }
    }

    fun showBrowseFragment() {
        // Replace Compose content with Leanback Fragment
        val container = android.widget.FrameLayout(this).apply {
            id = android.R.id.content
        }
        setContentView(container)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, ru.twicker.lostfilmtv.ui.MainBrowseFragment())
            .commit()
    }
}

@Composable
fun LostFilmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary       = LF_Red,
            onPrimary     = LF_OnPrimary,
            secondary     = LF_RedLight,
            background    = LF_PrimaryDark,
            surface       = LF_Surface,
            surfaceVariant= LF_SurfaceVar,
            onBackground  = LF_TextPrimary,
            onSurface     = LF_TextPrimary,
            onSurfaceVariant = LF_TextSecondary,
            error         = LF_RedLight,
        ),
        content = content
    )
}

private fun freshCaptchaUrl() = "https://www.lostfilm.tv/simple_captcha.php?${System.currentTimeMillis()}"

// ─── States ───────────────────────────────────────────────────────────────────
private sealed class Screen {
    object Loading : Screen()
    data class Login(
        val error: String? = null,
        val busy: Boolean = false,
        val captchaBitmap: Bitmap? = null
    ) : Screen()
    data class Episodes(val list: List<Episode>) : Screen()
    data class Error(val message: String) : Screen()
}

private sealed class TorrentDialog {
    object None : TorrentDialog()
    object Loading : TorrentDialog()
    data class Loaded(val episode: Episode, val entries: List<TorrentEntry>) : TorrentDialog()
    data class NeedAuth(val episode: Episode) : TorrentDialog()
    data class Err(val episode: Episode, val message: String) : TorrentDialog()
}

// ─── Main Screen ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(repository: LostFilmRepository) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
    var statusMsg by remember { mutableStateOf("Запуск ciadpi...") }
    var activeClient by remember { mutableStateOf<okhttp3.OkHttpClient?>(null) }
    var torrentDialog by remember { mutableStateOf<TorrentDialog>(TorrentDialog.None) }
    val scope = rememberCoroutineScope()
    val cookieJar = remember { LostFilmAuth.cookieJar(context) }

    // saved email/pass for CAPTCHA re-submit
    var savedEmail by remember { mutableStateOf("") }
    var savedPass  by remember { mutableStateOf("") }

    // ── helper: fetch captcha bitmap ──────────────────────────────────────
    suspend fun loadCaptcha(client: okhttp3.OkHttpClient): Bitmap? =
        withContext(Dispatchers.IO) { LostFilmAuth.fetchCaptchaBitmap(client) }

    // ── doLogin helper ───────────────────────────────────────────────────────
    suspend fun doLogin(email: String, pass: String, captcha: String? = null) {
        savedEmail = email; savedPass = pass
        screen = Screen.Login(busy = true, captchaBitmap = (screen as? Screen.Login)?.captchaBitmap)
        val client = activeClient ?: NetworkClient.createCiadpiClient(cookieJar).also { activeClient = it }

        Log.d(TAG, "doLogin: captcha=${captcha != null}")

        val result = withContext(Dispatchers.IO) {
            LostFilmAuth.login(context, client, email, pass, captcha)
        }
        when (result) {
            is LostFilmAuth.LoginResult.Success -> {
                statusMsg = "Загрузка..."
                val eps = withContext(Dispatchers.IO) {
                    try { repository.getRecentEpisodes(client) } catch (e: Exception) { null }
                }
                if (eps != null) { (context as? MainActivity)?.showBrowseFragment(); return@doLogin }
                else screen = Screen.Login(error = "Авторизация прошла, но загрузка не удалась")
            }
            is LostFilmAuth.LoginResult.NeedCaptcha -> {
                Log.d(TAG, "NeedCaptcha → fetching bitmap")
                val bmp = loadCaptcha(client)
                screen = Screen.Login(captchaBitmap = bmp)
            }
            is LostFilmAuth.LoginResult.Error -> {
                Log.d(TAG, "Login error: ${result.message}, fetching fresh captcha")
                // Re-prime session + fetch new captcha
                withContext(Dispatchers.IO) { LostFilmAuth.login(context, client, email, pass, null) }
                val bmp = loadCaptcha(client)
                screen = Screen.Login(error = result.message, captchaBitmap = bmp)
            }
        }
    }

    // ── Startup ──────────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        val ok = withContext(Dispatchers.IO) { CiadpiRunner.start(context) }
        if (!ok) { screen = Screen.Error("ciadpi не запустился"); return@LaunchedEffect }
        val client = NetworkClient.createCiadpiClient(cookieJar).also { activeClient = it }

        val creds = LostFilmAuth.loadCredentials(context)
        savedEmail = creds?.first ?: ""; savedPass = creds?.second ?: ""

        // 1. Try existing session cookies — check if truly authenticated
        if (cookieJar.hasSession()) {
            statusMsg = "Проверка сессии..."
            val authed = withContext(Dispatchers.IO) { LostFilmAuth.isAuthenticated(client) }
            if (authed) {
                statusMsg = "Загрузка..."
                val eps = withContext(Dispatchers.IO) {
                    try { repository.getRecentEpisodes(client) } catch (_: Exception) { null }
                }
                if (eps != null) { (context as? MainActivity)?.showBrowseFragment(); return@LaunchedEffect }
                else { screen = Screen.Error("Авторизован, но не удалось загрузить список"); return@LaunchedEffect }
                return@LaunchedEffect
            }
            Log.d(TAG, "Session expired, need fresh login")
        }

        // 2. Session dead → fresh login with captcha
        cookieJar.clearSession()
        withContext(Dispatchers.IO) { LostFilmAuth.initSession(client) }
        val bmp = loadCaptcha(client)
        screen = Screen.Login(captchaBitmap = bmp)
    }

    // ── Torrent dialog ────────────────────────────────────────────────────────
    when (val td = torrentDialog) {
        is TorrentDialog.None -> {}
        is TorrentDialog.Loading -> AlertDialog(
            onDismissRequest = { torrentDialog = TorrentDialog.None },
            title = { Text("Загрузка...") },
            text = { Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LF_Red) } },
            confirmButton = { TextButton(onClick = { torrentDialog = TorrentDialog.None }) { Text("Отмена") } }
        )
        is TorrentDialog.Loaded -> AlertDialog(
            onDismissRequest = { torrentDialog = TorrentDialog.None },
            title = { Text(td.episode.title) },
            text = {
                if (td.entries.isEmpty()) Text("Торренты не найдены.")
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(td.entries) { t ->
                        OutlinedCard(Modifier.fillMaxWidth().clickable {
                            torrentDialog = TorrentDialog.None
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.url)))
                        }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(t.quality, fontWeight = FontWeight.Bold, color = LF_RedLight)
                                if (t.sizeMb.isNotBlank()) Text(t.sizeMb, style = MaterialTheme.typography.bodySmall, color = LF_TextSecondary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { torrentDialog = TorrentDialog.None }) { Text("Закрыть") } }
        )
        is TorrentDialog.NeedAuth -> AlertDialog(
            onDismissRequest = { torrentDialog = TorrentDialog.None },
            title = { Text("Требуется авторизация") },
            text = { Text("Для скачивания торрентов нужно войти в LostFilm.TV.") },
            confirmButton = { Button(onClick = { torrentDialog = TorrentDialog.None; screen = Screen.Login() }, colors = ButtonDefaults.buttonColors(containerColor = LF_Red)) { Text("Войти") } },
            dismissButton = { TextButton(onClick = { torrentDialog = TorrentDialog.None }) { Text("Закрыть") } }
        )
        is TorrentDialog.Err -> AlertDialog(
            onDismissRequest = { torrentDialog = TorrentDialog.None },
            title = { Text("Ошибка") }, text = { Text(td.message) },
            confirmButton = { TextButton(onClick = { torrentDialog = TorrentDialog.None }) { Text("OK") } }
        )
    }

    // ── Scaffold ──────────────────────────────────────────────────────────────
    Scaffold(
        containerColor = LF_PrimaryDark,
        topBar = {
            TopAppBar(
                title = { Text("LostFilm", color = LF_Red, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LF_Primary),
                actions = {
                    if (screen is Screen.Episodes) {
                        TextButton(onClick = {
                            scope.launch {
                                val client = activeClient ?: return@launch
                                val eps = withContext(Dispatchers.IO) { try { repository.getRecentEpisodes(client) } catch (_: Exception) { null } }
                                if (eps != null) screen = Screen.Episodes(eps)
                            }
                        }) { Text("↻", color = LF_TextPrimary) }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = screen) {
                is Screen.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = LF_Red)
                    Spacer(Modifier.height(16.dp))
                    Text(statusMsg, color = LF_TextSecondary)
                }
                is Screen.Login -> LoginPanel(
                    error = s.error, busy = s.busy, captchaBitmap = s.captchaBitmap,
                    onLogin = { email, pass, captcha -> scope.launch { doLogin(email, pass, captcha) } },
                    onRefreshCaptcha = { scope.launch {
                        val client = activeClient ?: return@launch
                        val bmp = loadCaptcha(client)
                        screen = Screen.Login(captchaBitmap = bmp, error = (screen as? Screen.Login)?.error)
                    }},
                    savedEmail = savedEmail, savedPass = savedPass
                )
                is Screen.Episodes -> {
                    // Handled by Leanback Fragment — should not reach here
                }
                is Screen.Error -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ошибка", style = MaterialTheme.typography.titleMedium, color = LF_RedLight)
                    Spacer(Modifier.height(8.dp))
                    Text(s.message, color = LF_TextSecondary)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { scope.launch {
                            statusMsg = "Загрузка..."
                            screen = Screen.Loading
                            val client = activeClient ?: return@launch
                            val eps = withContext(Dispatchers.IO) {
                                try { repository.getRecentEpisodes(client) } catch (_: Exception) { null }
                            }
                            if (eps != null) (context as? MainActivity)?.showBrowseFragment()
                            else screen = Screen.Error("Не удалось загрузить")
                        }},
                        colors = ButtonDefaults.buttonColors(containerColor = LF_Red)
                    ) { Text("Повторить", color = Color.White) }
                }
            }
        }
    }
}

// ─── Login Panel ──────────────────────────────────────────────────────────────
@Composable
fun LoginPanel(
    error: String?, busy: Boolean, captchaBitmap: Bitmap?,
    onLogin: (String, String, String?) -> Unit,
    onRefreshCaptcha: () -> Unit,
    savedEmail: String, savedPass: String
) {
    var email   by remember { mutableStateOf(savedEmail.ifBlank { "user@example.com" }) }
    var pass    by remember { mutableStateOf(savedPass.ifBlank { "REDACTED" }) }
    var captcha by remember { mutableStateOf("") }
    val captchaFocus = remember { FocusRequester() }

    // Clear captcha field and re-focus when new captcha image arrives
    LaunchedEffect(captchaBitmap) {
        captcha = ""
        kotlinx.coroutines.delay(300)
        try { captchaFocus.requestFocus() } catch (_: Exception) {}
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("LostFilm.TV", style = MaterialTheme.typography.headlineSmall, color = LF_Red, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // ── Two-column row: [email+pass] | [captcha image + captcha input] ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Left column : credentials
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("Email") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    enabled = !busy
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it },
                    label = { Text("Пароль") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !busy
                )
            }
            // Right column : captcha
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (captchaBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = captchaBitmap.asImageBitmap(),
                        contentDescription = "CAPTCHA",
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        contentScale = ContentScale.FillWidth
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = LF_Red, strokeWidth = 2.dp)
                    }
                }
                OutlinedTextField(
                    value = captcha, onValueChange = { captcha = it },
                    label = { Text("Код с картинки") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(captchaFocus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !busy
                )
                TextButton(onClick = { onRefreshCaptcha(); captcha = "" },
                    modifier = Modifier.align(Alignment.End),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text("↻ Обновить", color = LF_RedLight, style = MaterialTheme.typography.labelSmall) }
            }
        }

        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = LF_RedLight)
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onLogin(email.trim(), pass, captcha.ifBlank { null }) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && email.isNotBlank() && pass.isNotBlank() && captcha.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = LF_Red)
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            else Text("Войти", color = Color.White)
        }
    }
}

// ─── Episode Card ─────────────────────────────────────────────────────────────
@Composable
fun EpisodeCard(episode: Episode, imageLoader: ImageLoader, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
        colors = CardDefaults.cardColors(containerColor = LF_SurfaceVar)
    ) {
        Row(Modifier.height(150.dp)) {
            // Poster 115x150dp — matches mobile_episode_card.xml
            Box(Modifier.width(115.dp).fillMaxHeight().padding(10.dp)) {
                if (episode.imageUrl.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(episode.imageUrl).crossfade(true).build(),
                        imageLoader = imageLoader,
                        contentDescription = episode.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            // Text area
            Box(Modifier.weight(1f).fillMaxHeight().padding(top = 20.dp, bottom = 20.dp, start = 10.dp, end = 60.dp)) {
                Column {
                    Text(episode.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp), fontWeight = FontWeight.Bold, color = LF_TextPrimary, maxLines = 2)
                    if (episode.titleEn.isNotEmpty()) Text(episode.titleEn, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 17.sp), color = LF_TextSecondary, maxLines = 1)
                    if (episode.episodeInfo.isNotEmpty()) Text(episode.episodeInfo, style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, fontStyle = FontStyle.Italic), color = LF_TextSecondary)
                }
                if (episode.date.isNotEmpty()) {
                    Text(episode.date, style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = LF_TextSecondary, modifier = Modifier.align(Alignment.BottomStart))
                }
            }
        }
    }
}
