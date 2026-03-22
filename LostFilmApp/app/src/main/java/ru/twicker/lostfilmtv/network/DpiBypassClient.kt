package ru.twicker.lostfilmtv.network

import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

private const val TAG = "DpiBypass"

// ─── V6: SNI-splitting fragmentation ───────────────────────────────

/**
 * Socket factory that wraps raw TCP sockets with a fragmenting output stream.
 * When the SSL engine writes the ClientHello to the underlying socket,
 * our wrapper splits it at the exact position of the SNI hostname,
 * preventing DPI from matching the domain name in a single TCP segment.
 */
class DpiBypassSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = DpiSocket()
    override fun createSocket(host: String, port: Int): Socket {
        val s = DpiSocket(); s.connect(InetSocketAddress(host, port)); return s
    }
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        val s = DpiSocket(); s.bind(InetSocketAddress(localHost, localPort)); s.connect(InetSocketAddress(host, port)); return s
    }
    override fun createSocket(host: InetAddress, port: Int): Socket {
        val s = DpiSocket(); s.connect(InetSocketAddress(host, port)); return s
    }
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        val s = DpiSocket(); s.bind(InetSocketAddress(localAddress, localPort)); s.connect(InetSocketAddress(address, port)); return s
    }
}

class DpiSocket : Socket() {
    private var wrappedOut: OutputStream? = null

    override fun getOutputStream(): OutputStream {
        if (wrappedOut == null) {
            wrappedOut = SniSplittingOutputStream(super.getOutputStream())
        }
        return wrappedOut!!
    }

    override fun connect(endpoint: SocketAddress?) {
        super.connect(endpoint)
        tcpNoDelay = true
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        super.connect(endpoint, timeout)
        tcpNoDelay = true
    }
}

/**
 * Searches for the SNI hostname in the raw ClientHello bytes and splits
 * the TCP write right in the middle of the hostname string.
 * DPI sees "www.lo" in one segment and "stfilm.tv" in the next — no match.
 */
class SniSplittingOutputStream(private val delegate: OutputStream) : OutputStream() {

    private var firstWriteDone = false

    private val targetHosts = listOf(
        "www.lostfilm.tv", "lostfilm.tv", "static.lostfilm.tv",
        "www.lostfilm.run", "www.lostfilm.win", "www.lostfilm.uno"
    )

    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray) = write(b, 0, b.size)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (!firstWriteDone && len > 0 && (b[off].toInt() and 0xFF) == 0x16) {
            firstWriteDone = true
            val data = b.copyOfRange(off, off + len)

            for (host in targetHosts) {
                val hostBytes = host.toByteArray(Charsets.US_ASCII)
                val idx = findBytes(data, hostBytes)
                if (idx >= 0) {
                    // Split in the middle of the hostname
                    val splitPos = idx + hostBytes.size / 3
                    if (splitPos in 1 until len) {
                        Log.d(TAG, "SNI split: '$host' at pos=$splitPos/$len")
                        delegate.write(b, off, splitPos)
                        delegate.flush()
                        try { Thread.sleep(100) } catch (_: InterruptedException) {}
                        delegate.write(b, off + splitPos, len - splitPos)
                        delegate.flush()
                        Log.d(TAG, "ClientHello split: $splitPos + ${len - splitPos} bytes")
                        return
                    }
                }
            }
            // No target hostname found, send as-is
            Log.d(TAG, "No SNI target in ClientHello ($len bytes)")
            delegate.write(b, off, len)
        } else {
            delegate.write(b, off, len)
        }
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()

    private fun findBytes(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }
}

// ─── Proxy auto-discovery (fallback) ───────────────────────────────

object ProxyConfig {
    @Volatile var activeProxy: Proxy = Proxy.NO_PROXY; private set
    @Volatile var proxyHost: String = ""; private set
    @Volatile var proxyPort: Int = 0; private set

    fun setProxy(host: String, port: Int) {
        proxyHost = host
        proxyPort = port
        activeProxy = if (host.isNotBlank() && port > 0) {
            Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        } else Proxy.NO_PROXY
        Log.d(TAG, "Proxy set to: $host:$port")
    }

    /**
     * Auto-discover a working HTTPS proxy from public APIs.
     */
    suspend fun autoDiscover(): Boolean {
        Log.d(TAG, "Auto-discovering proxies...")
        val plainClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS).build()

        val candidates = mutableListOf<Pair<String, Int>>()

        // Source 1: spys.me
        try {
            val text = plainClient.newCall(Request.Builder().url("https://spys.me/proxy.txt").build()).execute().body?.string() ?: ""
            for (line in text.lines()) {
                val m = Regex("""^(\d+\.\d+\.\d+\.\d+):(\d+)\s""").find(line)
                if (m != null) {
                    val port = m.groupValues[2].toIntOrNull() ?: continue
                    if (line.contains("-S") || line.contains("+S")) candidates.add(m.groupValues[1] to port)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "spys.me: ${e.message}") }

        // Source 2: proxyscrape
        try {
            val text = plainClient.newCall(Request.Builder().url("https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=5000&ssl=yes&anonymity=all").build()).execute().body?.string() ?: ""
            for (line in text.lines()) {
                val parts = line.trim().split(":")
                if (parts.size == 2) {
                    val port = parts[1].toIntOrNull() ?: continue
                    candidates.add(parts[0] to port)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "proxyscrape: ${e.message}") }

        Log.d(TAG, "Proxy candidates: ${candidates.size}")

        for ((host, port) in candidates.take(20)) {
            try {
                val tc = OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS).build()
                val code = tc.newCall(Request.Builder().url("https://www.lostfilm.tv/").head().build()).execute().code
                if (code in 200..403) {
                    Log.d(TAG, "✓ Proxy $host:$port works ($code)")
                    setProxy(host, port)
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "✗ $host:$port: ${e.message}")
            }
        }
        return false
    }
}

// ─── Network Client (v6 + proxy fallback) ──────────────────────────

object NetworkClient {
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    /** v6 client: SNI-splitting + DoH (IPv4 only) */
    fun createV6Client(): OkHttpClient {
        val bootstrapClient = OkHttpClient.Builder().build()

        // DoH resolver filtered to IPv4
        val doh = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://1.1.1.1/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
            .build()

        val ipv4Dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addrs = doh.lookup(hostname)
                val v4 = addrs.filter { it is Inet4Address }
                Log.d(TAG, "DoH: $hostname → ${v4.joinToString()}")
                return v4.ifEmpty { addrs }
            }
        }

        return OkHttpClient.Builder()
            .socketFactory(DpiBypassSocketFactory())
            .dns(ipv4Dns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .addInterceptor(loggingInterceptor())
            .build()
    }

    /** Proxied client */
    fun createProxiedClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .addInterceptor(loggingInterceptor())

        if (ProxyConfig.activeProxy != Proxy.NO_PROXY) {
            builder.proxy(ProxyConfig.activeProxy)
        }
        return builder.build()
    }

    val okHttpClient: OkHttpClient by lazy { createV6Client() }

    private fun loggingInterceptor() = okhttp3.Interceptor { chain ->
        val req = chain.request()
        Log.d(TAG, "→ ${req.method} ${req.url}")
        try {
            val resp = chain.proceed(req)
            Log.d(TAG, "← ${resp.code} ${req.url}")
            resp
        } catch (e: IOException) {
            Log.e(TAG, "✗ ${req.url}: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }
}
