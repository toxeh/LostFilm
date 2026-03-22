package ru.twicker.lostfilmtv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import ru.twicker.lostfilmtv.network.CiadpiRunner
import ru.twicker.lostfilmtv.network.Episode
import ru.twicker.lostfilmtv.network.LostFilmAuth
import ru.twicker.lostfilmtv.network.LostFilmRepository
import ru.twicker.lostfilmtv.network.NetworkClient
import ru.twicker.lostfilmtv.network.TorrentEntry

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    private val repository = LostFilmRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LostFilmAppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(repository)
                }
            }
        }
    }
}

@Composable
fun LostFilmAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

// ─── States ───────────────────────────────────────────────────────────────────

private sealed class Screen {
    object Loading : Screen()
    data class Login(val error: String? = null, val busy: Boolean = false) : Screen()
    data class Episodes(val list: List<Episode>) : Screen()
    data class Error(val message: String) : Screen()
}

// ─── Main Screen ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(repository: LostFilmRepository) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
    var statusMsg by remember { mutableStateOf("Запуск ciadpi...") }
    var activeClient by remember { mutableStateOf<okhttp3.OkHttpClient?>(null) }

    // Torrent picker dialog state
    var torrentEpisode by remember { mutableStateOf<Episode?>(null) }
    var torrents by remember { mutableStateOf<List<TorrentEntry>>(emptyList()) }
    var loadingTorrents by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val cookieJar = remember { LostFilmAuth.cookieJar(context) }

    // ── Startup ──────────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        val ok = withContext(Dispatchers.IO) { CiadpiRunner.start(context) }
        if (!ok) { screen = Screen.Error("ciadpi не запустился"); return@LaunchedEffect }

        val jar = LostFilmAuth.cookieJar(context)
        val client = NetworkClient.createCiadpiClient(jar)
        activeClient = client

        // Try loading with saved session
        statusMsg = "Загрузка..."
        val episodes = withContext(Dispatchers.IO) {
            try { repository.getRecentEpisodes(client) } catch (e: Exception) { null }
        }

        if (episodes != null) {
            screen = Screen.Episodes(episodes)
        } else {
            // Check saved credentials
            val creds = LostFilmAuth.loadCredentials(context)
            if (creds != null) {
                statusMsg = "Авторизация..."
                val err = withContext(Dispatchers.IO) {
                    LostFilmAuth.login(context, client, creds.first, creds.second)
                }
                if (err == null) {
                    val eps = withContext(Dispatchers.IO) {
                        try { repository.getRecentEpisodes(client) } catch (e: Exception) { null }
                    }
                    screen = if (eps != null) Screen.Episodes(eps) else Screen.Login()
                } else {
                    screen = Screen.Login(error = "Сохранённый логин не работает")
                }
            } else {
                screen = Screen.Login()
            }
        }
    }

    // ── Torrent picker dialog ─────────────────────────────────────────────────
    torrentEpisode?.let { ep ->
        AlertDialog(
            onDismissRequest = { torrentEpisode = null; torrents = emptyList() },
            title = { Text(ep.title) },
            text = {
                if (loadingTorrents) {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (torrents.isEmpty()) {
                    Text("Торренты не найдены.\nВозможно, требуется авторизация.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(torrents) { t ->
                            OutlinedCard(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    torrentEpisode = null
                                    torrents = emptyList()
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(t.url))
                                    context.startActivity(intent)
                                }
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(t.quality, style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold)
                                    if (t.sizeMb.isNotBlank()) {
                                        Text(t.sizeMb, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { torrentEpisode = null; torrents = emptyList() }) { Text("Закрыть") }
            }
        )
    }

    // ── Scaffold ──────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LostFilm") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    if (screen is Screen.Episodes) {
                        TextButton(onClick = {
                            scope.launch {
                                val client = activeClient ?: return@launch
                                val eps = withContext(Dispatchers.IO) {
                                    try { repository.getRecentEpisodes(client) } catch (e: Exception) { null }
                                }
                                if (eps != null) screen = Screen.Episodes(eps)
                            }
                        }) { Text("↻") }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = screen) {
                is Screen.Loading -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(statusMsg, style = MaterialTheme.typography.bodyMedium)
                }

                is Screen.Login -> LoginPanel(
                    error = s.error,
                    busy = s.busy,
                    onLogin = { email, pass ->
                        screen = Screen.Login(busy = true)
                        scope.launch {
                            val client = activeClient ?: NetworkClient.createCiadpiClient(cookieJar)
                                .also { activeClient = it }
                            val err = withContext(Dispatchers.IO) {
                                LostFilmAuth.login(context, client, email, pass)
                            }
                            if (err != null) {
                                screen = Screen.Login(error = err)
                            } else {
                                val eps = withContext(Dispatchers.IO) {
                                    try { repository.getRecentEpisodes(client) } catch (e: Exception) { null }
                                }
                                screen = if (eps != null) Screen.Episodes(eps)
                                else Screen.Login(error = "Авторизация прошла, но загрузка не удалась")
                            }
                        }
                    }
                )

                is Screen.Episodes -> {
                    val client = activeClient ?: NetworkClient.createCiadpiClient(cookieJar)
                    val imgLoader = ImageLoader.Builder(LocalContext.current).okHttpClient(client).build()
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(s.list) { ep ->
                            EpisodeCard(ep, imgLoader, onClick = {
                                torrentEpisode = ep
                                loadingTorrents = true
                                torrents = emptyList()
                                scope.launch {
                                    torrents = withContext(Dispatchers.IO) {
                                        repository.getTorrents(client, ep.episodeCode)
                                    }
                                    loadingTorrents = false
                                }
                            })
                        }
                    }
                }

                is Screen.Error -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Ошибка", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(s.message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ─── Login Panel ──────────────────────────────────────────────────────────────

@Composable
fun LoginPanel(error: String?, busy: Boolean, onLogin: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var pass  by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Войти в LostFilm.TV", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = !busy
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pass, onValueChange = { pass = it },
            label = { Text("Пароль") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !busy
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onLogin(email.trim(), pass) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && email.isNotBlank() && pass.isNotBlank()
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Войти")
        }
    }
}

// ─── Episode Card ─────────────────────────────────────────────────────────────

@Composable
fun EpisodeCard(episode: Episode, imageLoader: ImageLoader, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row {
            if (episode.imageUrl.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(episode.imageUrl).crossfade(true).build(),
                    imageLoader = imageLoader,
                    contentDescription = episode.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(90.dp, 130.dp)
                )
            } else {
                Box(Modifier.size(90.dp, 130.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(episode.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (episode.titleEn.isNotEmpty()) {
                    Text(episode.titleEn, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (episode.episodeInfo.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(episode.episodeInfo, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (episode.date.isNotEmpty()) {
                    Text(episode.date, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
