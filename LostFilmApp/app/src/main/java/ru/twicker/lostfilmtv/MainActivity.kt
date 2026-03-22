package ru.twicker.lostfilmtv

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.twicker.lostfilmtv.network.CiadpiRunner
import ru.twicker.lostfilmtv.network.Episode
import ru.twicker.lostfilmtv.network.LostFilmRepository
import ru.twicker.lostfilmtv.network.NetworkClient

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

@Composable
fun MainScreen(repository: LostFilmRepository) {
    val context = LocalContext.current
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("Запуск ciadpi...") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var activeClient by remember { mutableStateOf<okhttp3.OkHttpClient?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // Startup: embedded ciadpi SOCKS5 proxy (--tlsrec 1+s --auto=torst)
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            val ok = withContext(Dispatchers.IO) { CiadpiRunner.start(context) }
            if (ok) {
                statusMessage = "Обход DPI..."
                val client = NetworkClient.createCiadpiClient()
                val result = withContext(Dispatchers.IO) {
                    try { repository.getRecentEpisodes(client).also { Log.d(TAG, "ciadpi: ${it.size} episodes") } }
                    catch (e: Exception) { Log.d(TAG, "ciadpi failed: ${e.message}"); null }
                }
                if (result != null) {
                    episodes = result; activeClient = client; isLoading = false
                    return@launch
                }
                CiadpiRunner.stop()
            }
            isLoading = false
            errorMessage = "ciadpi не смог обойти блокировку.\nПерезапустите приложение."
        }
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("LostFilm") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    if (!isLoading && errorMessage == null) {
                        TextButton(onClick = {
                            isLoading = true; errorMessage = null
                            coroutineScope.launch {
                                activeClient?.let { client ->
                                    try {
                                        episodes = withContext(Dispatchers.IO) { repository.getRecentEpisodes(client) }
                                    } catch (e: Exception) { errorMessage = e.message }
                                    isLoading = false
                                }
                            }
                        }) { Text("↻") }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                isLoading -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
                }

                errorMessage != null -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Ошибка", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(errorMessage!!, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = {
                        isLoading = true; errorMessage = null
                        coroutineScope.launch {
                            val ok = withContext(Dispatchers.IO) { CiadpiRunner.start(context) }
                            if (ok) {
                                val client = NetworkClient.createCiadpiClient()
                                try {
                                    episodes = withContext(Dispatchers.IO) { repository.getRecentEpisodes(client) }
                                    activeClient = client
                                } catch (e: Exception) { errorMessage = e.message }
                            } else { errorMessage = "ciadpi не запустился" }
                            isLoading = false
                        }
                    }) { Text("Повторить") }
                }

                episodes.isEmpty() -> Text("Нет новых серий", modifier = Modifier.align(Alignment.Center))

                else -> {
                    val imgClient = activeClient ?: NetworkClient.createCiadpiClient()
                    val imageLoader = ImageLoader.Builder(LocalContext.current).okHttpClient(imgClient).build()
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(episodes) { episode -> EpisodeCard(episode, imageLoader) }
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeCard(episode: Episode, imageLoader: ImageLoader) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(0.dp)) {
            // Poster
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
            // Info
            Column(
                Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
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
                    Spacer(Modifier.height(2.dp))
                    Text(episode.date, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
