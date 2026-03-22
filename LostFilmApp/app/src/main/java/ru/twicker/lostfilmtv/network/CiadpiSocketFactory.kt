package ru.twicker.lostfilmtv.network

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import javax.net.SocketFactory

private const val TAG_SF = "CiadpiSF"

/**
 * Custom SocketFactory that connects through ciadpi SOCKS5 proxy
 * while performing DNS resolution via DoH before the SOCKS5 handshake.
 *
 * WHY: Java's SOCKS5 implementation sends the hostname to the proxy server
 * for remote resolution. ciadpi cannot resolve DNS on Android (no /etc/resolv.conf).
 * This factory resolves DNS via DoH first, then initiates SOCKS5 CONNECT with the IP.
 */
class CiadpiSocketFactory(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val doH: Dns
) : SocketFactory() {

    override fun createSocket(): Socket = CiadpiSocket(proxyHost, proxyPort, doH)
    override fun createSocket(host: String, port: Int) = createSocket().also {
        it.connect(InetSocketAddress(host, port))
    }
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) = createSocket(host, port)
    override fun createSocket(host: InetAddress, port: Int) = createSocket().also {
        it.connect(InetSocketAddress(host, port))
    }
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int) =
        createSocket(address, port)
}

/**
 * A Socket that, when connected to a hostname, resolves it via DoH then
 * does a manual SOCKS5 handshake (no-auth) with the ciadpi proxy using the IP.
 */
private class CiadpiSocket(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val doH: Dns
) : Socket() {

    private val rawSocket = Socket()
    private var _inputStream: InputStream? = null
    private var _outputStream: OutputStream? = null

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        val addr = endpoint as? InetSocketAddress
            ?: throw SocketException("Expected InetSocketAddress")

        val targetHost = addr.hostString
        val targetPort = addr.port

        // Step 1: Resolve hostname via DoH → get first IPv4
        // (if OkHttp already passed a numeric IP, use it directly)
        val ip: InetAddress = try {
            runCatching { InetAddress.getByName(targetHost) }
                .getOrNull()
                ?.takeIf { it.hostAddress == targetHost }           // already a numeric IP?
                ?: doH.lookup(targetHost).firstOrNull()
                ?: throw SocketException("DoH: no address for $targetHost")
        } catch (e: Exception) {
            throw SocketException("DoH resolve failed for $targetHost: ${e.message}")
        }
        Log.d(TAG_SF, "SOCKS5 target: $targetHost → $ip")

        // Step 2: TCP connect to ciadpi SOCKS5 proxy
        rawSocket.connect(InetSocketAddress(proxyHost, proxyPort), if (timeout > 0) timeout else 10_000)
        rawSocket.soTimeout = if (timeout > 0) timeout else 30_000
        val sin  = rawSocket.inputStream
        val sout = rawSocket.outputStream

        // Step 3: SOCKS5 greeting — [VER=5, NMETHODS=1, METHOD=0(no-auth)]
        sout.write(byteArrayOf(0x05, 0x01, 0x00))
        sout.flush()
        val greeting = ByteArray(2)
        sin.readFully(greeting)
        if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
            throw SocketException("SOCKS5 auth negotiation failed: ${greeting.toHex()}")
        }

        // Step 4: SOCKS5 CONNECT — [VER=5, CMD=1, RSV=0, ATYP=1(IPv4), ip[4], port[2]]
        val ipBytes = ip.address   // always 4 bytes for IPv4
        val req = ByteArray(10)
        req[0] = 0x05  // VER
        req[1] = 0x01  // CMD CONNECT
        req[2] = 0x00  // RSV
        req[3] = 0x01  // ATYP IPv4
        System.arraycopy(ipBytes, 0, req, 4, 4)
        req[8] = ((targetPort shr 8) and 0xFF).toByte()
        req[9] = (targetPort and 0xFF).toByte()
        sout.write(req); sout.flush()

        // Step 5: Read SOCKS5 response
        val rep = ByteArray(10)
        sin.readFully(rep)
        if (rep[1] != 0x00.toByte()) {
            val err = rep[1].toInt() and 0xFF
            throw SocketException("SOCKS5 CONNECT failed, code=$err for $targetHost ($ip:$targetPort)")
        }

        Log.d(TAG_SF, "SOCKS5 tunnel: $targetHost ($ip) :$targetPort via $proxyHost:$proxyPort")
        _inputStream  = sin
        _outputStream = sout
    }

    override fun getInputStream():  InputStream  = _inputStream  ?: rawSocket.inputStream
    override fun getOutputStream(): OutputStream = _outputStream ?: rawSocket.outputStream
    override fun close() { rawSocket.close() }
    override fun isClosed() = rawSocket.isClosed
    override fun isConnected() = rawSocket.isConnected

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

private fun InputStream.readFully(buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
        val n = read(buf, off, buf.size - off)
        if (n < 0) throw SocketException("Connection closed during SOCKS5 handshake")
        off += n
    }
}
