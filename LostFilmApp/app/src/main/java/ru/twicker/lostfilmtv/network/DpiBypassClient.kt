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

// ═══════════════════════════════════════════════════════════════════
// TLS RECORD SPLITTING — ByeDPI/ciadpi technique (--tlsrec)
//
// Key insight: we tried TCP-level fragmentation (splitting one TLS
// record across multiple TCP segments) but ТСПУ reassembles TCP.
//
// TLS record splitting is DIFFERENT: we rewrite the single TLS record
// into TWO separate TLS records, each with its own 5-byte header.
// DPI parses the FIRST TLS record looking for SNI — but we split
// it so SNI is in the SECOND record. DPI doesn't find it → no block.
//
// Original:  [16 03 01 LL LL] [ClientHello with SNI...]
// 
// After:     [16 03 01 AA AA] [first part of ClientHello...]
//            [16 03 01 BB BB] [rest of ClientHello with SNI...]
//
// This adds 5 bytes (the extra TLS record header). The TLS spec
// allows handshake messages to span multiple records. The server
// reassembles them correctly.
// ═══════════════════════════════════════════════════════════════════

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
            wrappedOut = TlsRecordSplittingOutputStream(super.getOutputStream())
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
 * Implements TLS record splitting (equivalent to ByeDPI --tlsrec).
 * 
 * When the SSL engine writes a ClientHello TLS record, we:
 * 1. Find the SNI hostname in the record payload
 * 2. Calculate a split point BEFORE the SNI (so record 1 has no SNI)
 * 3. Rewrite: one record → two records, each with its own TLS header
 * 4. Send record 1, pause, send record 2
 *
 * TLS record header format (5 bytes):
 *   [content_type: 1B] [version: 2B] [length: 2B]
 *   content_type = 0x16 (Handshake)
 *   version = 0x0301 (TLS 1.0 for compatibility)
 */
class TlsRecordSplittingOutputStream(private val delegate: OutputStream) : OutputStream() {

    private var firstWriteDone = false

    private val targetHosts = listOf(
        "www.lostfilm.tv", "lostfilm.tv", "static.lostfilm.tv"
    )

    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray) = write(b, 0, b.size)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (!firstWriteDone && len > 5 && (b[off].toInt() and 0xFF) == 0x16) {
            firstWriteDone = true

            // Parse TLS record header
            val contentType = b[off].toInt() and 0xFF     // 0x16
            val versionHi = b[off + 1].toInt() and 0xFF   // 0x03
            val versionLo = b[off + 2].toInt() and 0xFF   // 0x01
            val recordLen = ((b[off + 3].toInt() and 0xFF) shl 8) or (b[off + 4].toInt() and 0xFF)

            // Search for SNI hostname in the payload (after the 5-byte TLS header)
            val payload = b.copyOfRange(off + 5, off + 5 + recordLen)
            var sniOffset = -1
            var foundHost = ""

            for (host in targetHosts) {
                val hostBytes = host.toByteArray(Charsets.US_ASCII)
                val idx = findBytes(payload, hostBytes)
                if (idx >= 0) {
                    // Split 3 bytes into the SNI hostname (like --tlsrec 3+s)
                    sniOffset = idx + 3
                    foundHost = host
                    break
                }
            }

            if (sniOffset > 0 && sniOffset < recordLen) {
                // Split into two TLS records
                val part1Len = sniOffset
                val part2Len = recordLen - sniOffset

                Log.d(TAG, "TLS record split: '$foundHost' → rec1=$part1Len rec2=$part2Len (orig=$recordLen)")

                // Record 1: TLS header + first part of handshake (no SNI)
                val rec1 = ByteArray(5 + part1Len)
                rec1[0] = contentType.toByte()
                rec1[1] = versionHi.toByte()
                rec1[2] = versionLo.toByte()
                rec1[3] = ((part1Len shr 8) and 0xFF).toByte()
                rec1[4] = (part1Len and 0xFF).toByte()
                System.arraycopy(payload, 0, rec1, 5, part1Len)

                // Record 2: TLS header + rest of handshake (contains SNI)
                val rec2 = ByteArray(5 + part2Len)
                rec2[0] = contentType.toByte()
                rec2[1] = versionHi.toByte()
                rec2[2] = versionLo.toByte()
                rec2[3] = ((part2Len shr 8) and 0xFF).toByte()
                rec2[4] = (part2Len and 0xFF).toByte()
                System.arraycopy(payload, sniOffset, rec2, 5, part2Len)

                // Send record 1
                delegate.write(rec1)
                delegate.flush()
                Log.d(TAG, "Sent TLS record 1: ${rec1.size} bytes (no SNI)")

                // Brief pause to ensure separate TCP segments
                try { Thread.sleep(100) } catch (_: InterruptedException) {}

                // Send record 2
                delegate.write(rec2)
                delegate.flush()
                Log.d(TAG, "Sent TLS record 2: ${rec2.size} bytes (contains SNI)")
            } else {
                // No target hostname found, send as-is
                Log.d(TAG, "No SNI target in TLS record ($recordLen bytes)")
                delegate.write(b, off, len)
            }
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

// ═══════════════════════════════════════════════════════════════════
// PROXY AUTO-DISCOVERY (fallback)
// ═══════════════════════════════════════════════════════════════════

object ProxyConfig {
    @Volatile var activeProxy: Proxy = Proxy.NO_PROXY; private set
    @Volatile var proxyHost: String = ""; private set
    @Volatile var proxyPort: Int = 0; private set

    fun setProxy(host: String, port: Int) {
        proxyHost = host; proxyPort = port
        activeProxy = if (host.isNotBlank() && port > 0)
            Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)) else Proxy.NO_PROXY
        Log.d(TAG, "Proxy set to: $host:$port")
    }

    suspend fun autoDiscover(): Boolean {
        Log.d(TAG, "Auto-discovering proxies...")
        val c = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        val candidates = mutableListOf<Pair<String, Int>>()

        try {
            val text = c.newCall(Request.Builder().url("https://spys.me/proxy.txt").build()).execute().body?.string() ?: ""
            for (line in text.lines()) {
                val m = Regex("""^(\d+\.\d+\.\d+\.\d+):(\d+)\s""").find(line)
                if (m != null) { val port = m.groupValues[2].toIntOrNull() ?: continue
                    if (line.contains("-S") || line.contains("+S")) candidates.add(m.groupValues[1] to port) }
            }
        } catch (e: Exception) { Log.w(TAG, "spys.me: ${e.message}") }

        try {
            val text = c.newCall(Request.Builder().url("https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=5000&ssl=yes&anonymity=all").build()).execute().body?.string() ?: ""
            for (line in text.lines()) { val p = line.trim().split(":"); if (p.size == 2) { val port = p[1].toIntOrNull() ?: continue; candidates.add(p[0] to port) } }
        } catch (e: Exception) { Log.w(TAG, "proxyscrape: ${e.message}") }

        Log.d(TAG, "Proxy candidates: ${candidates.size}")
        for ((host, port) in candidates.take(20)) {
            try {
                val tc = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
                    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
                val code = tc.newCall(Request.Builder().url("https://www.lostfilm.tv/").head().build()).execute().code
                if (code in 200..403) { Log.d(TAG, "✓ Proxy $host:$port ($code)"); setProxy(host, port); return true }
            } catch (e: Exception) { Log.d(TAG, "✗ $host:$port: ${e.message}") }
        }
        return false
    }
}

// ═══════════════════════════════════════════════════════════════════
// NETWORK CLIENT
// ═══════════════════════════════════════════════════════════════════

object NetworkClient {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    /** v7: TLS record splitting + DoH (IPv4 only) */
    fun createV6Client(): OkHttpClient {
        val bootstrap = OkHttpClient.Builder().build()
        val doh = DnsOverHttps.Builder().client(bootstrap)
            .url("https://1.1.1.1/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1")).build()

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
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", UA).build()) }
            .addInterceptor(loggingInterceptor()).build()
    }

    fun createProxiedClient(): OkHttpClient {
        val b = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", UA).build()) }
            .addInterceptor(loggingInterceptor())
        if (ProxyConfig.activeProxy != Proxy.NO_PROXY) b.proxy(ProxyConfig.activeProxy)
        return b.build()
    }

    val okHttpClient: OkHttpClient by lazy { createV6Client() }

    private fun loggingInterceptor() = okhttp3.Interceptor { chain ->
        val req = chain.request()
        Log.d(TAG, "→ ${req.method} ${req.url}")
        try { val resp = chain.proceed(req); Log.d(TAG, "← ${resp.code} ${req.url}"); resp }
        catch (e: IOException) { Log.e(TAG, "✗ ${req.url}: ${e.javaClass.simpleName}: ${e.message}"); throw e }
    }
}
