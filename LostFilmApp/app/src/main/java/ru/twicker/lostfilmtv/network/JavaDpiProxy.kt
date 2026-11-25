package ru.twicker.lostfilmtv.network

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

private const val TAG = "JavaDpiProxy"

/**
 * Pure-Java SOCKS5 proxy with TLS ClientHello record splitting.
 * Replacement for ciadpi on devices where native binary is blocked by seccomp.
 *
 * The proxy:
 * 1. Accepts SOCKS5 connections on 127.0.0.1:<port>
 * 2. Connects to the target host
 * 3. Intercepts the first TLS ClientHello and splits it into 2 TLS records
 *    (cutting the SNI hostname in half) — same technique as ciadpi --tlsrec 1+s
 * 4. Pipes all further traffic transparently
 */
object JavaDpiProxy {

    var port: Int = 0
        private set
    private var serverSocket: ServerSocket? = null
    private var running = false

    private val targetHosts = listOf("www.lostfilm.tv", "lostfilm.tv", "static.lostfilm.tv")

    fun start(dohDns: okhttp3.Dns? = null): Boolean {
        if (running) return true
        return try {
            port = java.net.ServerSocket(0).use { it.localPort }
            val ss = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            running = true

            thread(isDaemon = true, name = "JavaDpiProxy-accept") {
                Log.i(TAG, "SOCKS5 proxy listening on 127.0.0.1:$port")
                while (running) {
                    try {
                        val client = ss.accept()
                        thread(isDaemon = true) { handleClient(client, dohDns) }
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            }

            // Wait for port to be ready
            Thread.sleep(100)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}", e)
            false
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    fun isRunning() = running

    private fun handleClient(client: Socket, dohDns: okhttp3.Dns?) {
        try {
            client.soTimeout = 30_000
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            // ── SOCKS5 handshake ──
            // Client greeting: VER NMETHODS METHODS...
            val ver = inp.read()
            if (ver != 0x05) { client.close(); return }
            val nMethods = inp.read()
            val methods = ByteArray(nMethods)
            inp.readFully(methods)
            // Reply: no auth required
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()

            // Client request: VER CMD RSV ATYP DST.ADDR DST.PORT
            val rVer = inp.read()
            val cmd = inp.read()
            val rsv = inp.read()
            val atyp = inp.read()

            if (rVer != 0x05 || cmd != 0x01) { // only CONNECT
                out.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush(); client.close(); return
            }

            val targetAddr: String
            when (atyp) {
                0x01 -> { // IPv4
                    val addr = ByteArray(4)
                    inp.readFully(addr)
                    targetAddr = InetAddress.getByAddress(addr).hostAddress!!
                }
                0x03 -> { // Domain
                    val len = inp.read()
                    val domain = ByteArray(len)
                    inp.readFully(domain)
                    targetAddr = String(domain, Charsets.US_ASCII)
                }
                0x04 -> { // IPv6
                    val addr = ByteArray(16)
                    inp.readFully(addr)
                    targetAddr = InetAddress.getByAddress(addr).hostAddress!!
                }
                else -> { client.close(); return }
            }
            val portHi = inp.read()
            val portLo = inp.read()
            val targetPort = (portHi shl 8) or portLo

            // Resolve domain via DoH if available
            val resolvedAddr = if (atyp == 0x03 && dohDns != null) {
                try {
                    val addrs = dohDns.lookup(targetAddr)
                    addrs.firstOrNull()?.hostAddress ?: targetAddr
                } catch (_: Exception) { targetAddr }
            } else targetAddr

            // Connect to target
            val remote = Socket()
            remote.connect(InetSocketAddress(resolvedAddr, targetPort), 10_000)
            remote.soTimeout = 30_000
            remote.tcpNoDelay = true
            try { remote.sendBufferSize = 1 } catch (_: Exception) {}

            // Reply: success
            val bindAddr = remote.localAddress.address
            val reply = ByteArray(10)
            reply[0] = 0x05; reply[1] = 0x00; reply[2] = 0x00; reply[3] = 0x01
            System.arraycopy(bindAddr.copyOf(4), 0, reply, 4, 4)
            reply[8] = ((remote.localPort shr 8) and 0xFF).toByte()
            reply[9] = (remote.localPort and 0xFF).toByte()
            out.write(reply)
            out.flush()

            // ── Pipe with TLS splitting on first write ──
            val remoteOut = remote.getOutputStream()
            val remoteInp = remote.getInputStream()

            // Client → Remote (with TLS splitting)
            val c2r = thread(isDaemon = true) {
                try {
                    var firstDone = false
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        if (!firstDone && n > 5 && (buf[0].toInt() and 0xFF) == 0x16) {
                            firstDone = true
                            splitAndSendTlsRecord(buf, n, remoteOut)
                        } else {
                            remoteOut.write(buf, 0, n)
                            remoteOut.flush()
                        }
                    }
                } catch (_: Exception) {}
                try { remote.shutdownOutput() } catch (_: Exception) {}
            }

            // Remote → Client (transparent)
            try {
                val buf = ByteArray(16384)
                while (true) {
                    val n = remoteInp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            } catch (_: Exception) {}

            try { client.shutdownOutput() } catch (_: Exception) {}
            c2r.join(1000)
            remote.close()
            client.close()
        } catch (e: Exception) {
            Log.d(TAG, "Client handler error: ${e.message}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Split a TLS record into two records, cutting at the middle of the SNI hostname.
     * This is the same technique as ciadpi --tlsrec 1+s.
     */
    private fun splitAndSendTlsRecord(data: ByteArray, len: Int, out: OutputStream) {
        val ct = data[0].toInt() and 0xFF    // content type (0x16 = handshake)
        val vh = data[1].toInt() and 0xFF    // version high
        val vl = data[2].toInt() and 0xFF    // version low
        val rlen = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)

        if (rlen + 5 > len || rlen < 20) {
            // Can't parse, send as-is
            out.write(data, 0, len); out.flush()
            return
        }

        val payload = data.copyOfRange(5, 5 + rlen)

        // Find SNI hostname in the payload
        var sniStart = -1
        var foundHost = ""
        for (host in targetHosts) {
            val idx = findBytes(payload, host.toByteArray(Charsets.US_ASCII))
            if (idx >= 0) { sniStart = idx; foundHost = host; break }
        }

        if (sniStart < 0) {
            Log.d(TAG, "No SNI found, sending as-is ($rlen bytes)")
            out.write(data, 0, len); out.flush()
            return
        }

        // Split at the middle of the hostname
        val splitAt = sniStart + foundHost.length / 2
        val len1 = splitAt
        val len2 = rlen - splitAt

        Log.d(TAG, "TLS split: '$foundHost' @$sniStart cut=$splitAt | $len1 + $len2")

        // First record (no complete SNI)
        val rec1 = ByteArray(5 + len1)
        rec1[0] = ct.toByte(); rec1[1] = vh.toByte(); rec1[2] = vl.toByte()
        rec1[3] = ((len1 shr 8) and 0xFF).toByte(); rec1[4] = (len1 and 0xFF).toByte()
        System.arraycopy(payload, 0, rec1, 5, len1)
        out.write(rec1); out.flush()

        // Delay to force separate TCP segments
        Thread.sleep(200)

        // Second record (rest with SNI tail)
        val rec2 = ByteArray(5 + len2)
        rec2[0] = ct.toByte(); rec2[1] = vh.toByte(); rec2[2] = vl.toByte()
        rec2[3] = ((len2 shr 8) and 0xFF).toByte(); rec2[4] = (len2 and 0xFF).toByte()
        System.arraycopy(payload, splitAt, rec2, 5, len2)
        out.write(rec2); out.flush()

        // Send remaining data after the TLS record (if any)
        val totalRecLen = 5 + rlen
        if (totalRecLen < len) {
            out.write(data, totalRecLen, len - totalRecLen)
            out.flush()
        }
    }

    private fun findBytes(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) { if (data[i + j] != pattern[j]) continue@outer }
            return i
        }
        return -1
    }

    private fun InputStream.readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = read(buf, off, buf.size - off)
            if (n < 0) throw java.io.IOException("EOF")
            off += n
        }
    }
}
