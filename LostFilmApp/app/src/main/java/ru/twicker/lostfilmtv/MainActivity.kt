package ru.twicker.lostfilmtv

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.twicker.lostfilmtv.network.Episode
import ru.twicker.lostfilmtv.network.LostFilmRepository
import ru.twicker.lostfilmtv.network.NetworkClient
import ru.twicker.lostfilmtv.network.ProxyConfig

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val repository = LostFilmRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("proxy", MODE_PRIVATE)
        val savedHost = prefs.getString("host", "") ?: ""
        val savedPort = prefs.getInt("port", 0)
        if (savedHost.isNotBlank() && savedPort > 0) {
            ProxyConfig.setProxy(savedHost, savedPort)
        }

        setContent {
            LostFilmAppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(repository, ::saveProxy)
                }
            }
        }
    }

    private fun saveProxy(host: String, port: Int) {
        getSharedPreferences("proxy", MODE_PRIVATE).edit()
            .putString("host", host).putInt("port", port).apply()
    }
}

@Composable
fun LostFilmAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

@Composable
fun MainScreen(repository: LostFilmRepository, saveProxy: (String, Int) -> Unit) {
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("Подключение...") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showProxyDialog by remember { mutableStateOf(false) }
    var proxyHostInput by remember { mutableStateOf("") }
    var proxyPortInput by remember { mutableStateOf("") }
    var activeClient by remember { mutableStateOf<okhttp3.OkHttpClient?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun loadData(client: okhttp3.OkHttpClient) {
        isLoading = true; errorMessage = null; activeClient = client
        coroutineScope.launch {
            try {
                episodes = withContext(Dispatchers.IO) { repository.getRecentEpisodes(client) }
                errorMessage = null
            } catch (e: Exception) {
                errorMessage = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "Load failed", e)
            }
            isLoading = false
        }
    }

    // Startup: v6 (SNI-split + DoH) → proxy fallback
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            // Step 1: Try v6 (SNI-splitting + DoH)
            statusMessage = "Обход DPI (SNI-splitting)..."
            val v6ok = withContext(Dispatchers.IO) {
                try {
                    val client = NetworkClient.createV6Client()
                    val resp = client.newCall(
                        okhttp3.Request.Builder().url("https://www.lostfilm.tv/").head().build()
                    ).execute()
                    Log.d(TAG, "v6 direct: code=${resp.code}")
                    resp.isSuccessful
                } catch (e: Exception) {
                    Log.d(TAG, "v6 failed: ${e.message}")
                    false
                }
            }

            if (v6ok) {
                statusMessage = "Прямое подключение через DPI bypass!"
                loadData(NetworkClient.createV6Client())
                return@launch
            }

            // Step 2: If proxy is saved, try it
            if (ProxyConfig.proxyHost.isNotBlank()) {
                statusMessage = "Подключение через прокси ${ProxyConfig.proxyHost}..."
                loadData(NetworkClient.createProxiedClient())
                return@launch
            }

            // Step 3: Auto-discover proxy
            statusMessage = "Автопоиск прокси-серверов..."
            val found = withContext(Dispatchers.IO) { ProxyConfig.autoDiscover() }
            if (found) {
                saveProxy(ProxyConfig.proxyHost, ProxyConfig.proxyPort)
                statusMessage = "Прокси: ${ProxyConfig.proxyHost}:${ProxyConfig.proxyPort}"
                loadData(NetworkClient.createProxiedClient())
            } else {
                isLoading = false
                errorMessage = "Не удалось подключиться. Укажите прокси вручную."
                showProxyDialog = true
            }
        }
    }

    if (showProxyDialog) {
        AlertDialog(
            onDismissRequest = { showProxyDialog = false },
            title = { Text("Настройка прокси") },
            text = {
                Column {
                    Text("LostFilm заблокирован. Укажите HTTPS-прокси.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(value = proxyHostInput, onValueChange = { proxyHostInput = it },
                        label = { Text("Хост") }, placeholder = { Text("proxy.example.com") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = proxyPortInput, onValueChange = { proxyPortInput = it.filter { c -> c.isDigit() } },
                        label = { Text("Порт") }, placeholder = { Text("8080") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    val port = proxyPortInput.toIntOrNull() ?: 0
                    if (proxyHostInput.isNotBlank() && port > 0) {
                        ProxyConfig.setProxy(proxyHostInput, port)
                        saveProxy(proxyHostInput, port)
                        showProxyDialog = false
                        loadData(NetworkClient.createProxiedClient())
                    }
                }, enabled = proxyHostInput.isNotBlank() && (proxyPortInput.toIntOrNull() ?: 0) > 0) {
                    Text("Подключить")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showProxyDialog = false
                    isLoading = true; statusMessage = "Автопоиск..."
                    coroutineScope.launch {
                        val ok = withContext(Dispatchers.IO) { ProxyConfig.autoDiscover() }
                        if (ok) { saveProxy(ProxyConfig.proxyHost, ProxyConfig.proxyPort); loadData(NetworkClient.createProxiedClient()) }
                        else { isLoading = false; errorMessage = "Прокси не найден" }
                    }
                }) { Text("Автопоиск") }
            }
        )
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("LostFilm") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                actions = { TextButton(onClick = { showProxyDialog = true }) { Text("⚙", color = MaterialTheme.colorScheme.onPrimaryContainer) } }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                isLoading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text(statusMessage)
                }
                errorMessage != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ошибка", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp)); Text(errorMessage!!, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showProxyDialog = true }) { Text("Прокси") }
                        OutlinedButton(onClick = { activeClient?.let { loadData(it) } }) { Text("Повторить") }
                    }
                }
                episodes.isEmpty() -> Text("Нет новых серий", modifier = Modifier.align(Alignment.Center))
                else -> {
                    val imgClient = activeClient ?: NetworkClient.createV6Client()
                    val imageLoader = ImageLoader.Builder(LocalContext.current).okHttpClient(imgClient).build()
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(episodes) { episode -> EpisodeCard(episode, imageLoader) }
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeCard(episode: Episode, imageLoader: ImageLoader) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
        Row(Modifier.padding(8.dp)) {
            if (episode.imageUrl.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(episode.imageUrl).crossfade(true).build(),
                    imageLoader = imageLoader, contentDescription = episode.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.size(100.dp, 150.dp)
                )
            } else Box(Modifier.size(100.dp, 150.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { Text(episode.title, style = MaterialTheme.typography.titleMedium) }
        }
    }
}
