package ru.twicker.lostfilmtv.network

import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

private const val TAG = "AuthBroadcast"
private const val MAGIC_REQ = "LFTV_AUTH_REQ"
private const val MAGIC_RESP = "LFTV_AUTH_RESP"
private const val MAX_PACKET = 8192  // cookies are typically ~500 bytes

/**
 * LAN-based auth + DPI strategy transfer via UDP broadcast.
 *
 * Requester (unauthorized device):
 *   1. discoverAndReceive() broadcasts LFTV_AUTH_REQ + device name
 *   2. Waits for LFTV_AUTH_RESP with DPI strategy + cookie string
 *
 * Listener (authorized device):
 *   1. startListening() loops on a DatagramSocket waiting for LFTV_AUTH_REQ
 *   2. Calls onRequest callback with sender IP and device name
 *   3. sendSession() sends LFTV_AUTH_RESP + dpi strategy + cookies (unicast)
 *
 * Response format: LFTV_AUTH_RESP\n<dpi_ttl>:<strategy_index>\n<cookies>
 */
data class LanAuthPayload(
    val dpiTtl: Int,
    val strategyIndex: Int,
    val email: String,
    val password: String,
    val cookies: String
)
object AuthBroadcast {

    // ── Requester side ──────────────────────────────────────────────

    private var requesterSocket: DatagramSocket? = null

    /**
     * Send a discovery broadcast and wait for an auth+strategy response.
     * Blocks the calling coroutine (call from Dispatchers.IO).
     *
     * @return parsed LanAuthPayload, or null on timeout / error
     */
    fun discoverAndReceive(port: Int, timeoutMs: Int = 15_000): LanAuthPayload? {
        return try {
            val sock = DatagramSocket(port).also { requesterSocket = it }
            sock.broadcast = true
            sock.soTimeout = timeoutMs

            // Send discovery broadcast
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val payload = "$MAGIC_REQ\n$deviceName".toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(
                payload, payload.size,
                InetAddress.getByName("255.255.255.255"), port
            )
            sock.send(packet)
            Log.d(TAG, "Sent discovery broadcast on port $port (device=$deviceName)")

            // Wait for response — loop to skip own echo and other non-response packets
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remaining = (deadline - System.currentTimeMillis()).toInt()
                if (remaining <= 0) break
                sock.soTimeout = remaining

                val buf = ByteArray(MAX_PACKET)
                val recvPacket = DatagramPacket(buf, buf.size)
                sock.receive(recvPacket)

                val data = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
                if (data.startsWith(MAGIC_RESP)) {
                    sock.close()
                    requesterSocket = null
                    val lines = data.removePrefix(MAGIC_RESP).trimStart('\n').split("\n", limit = 3)
                    if (lines.size >= 3) {
                        val stratParts = lines[0].split(":")
                        val ttl = stratParts.getOrNull(0)?.toIntOrNull() ?: -1
                        val idx = stratParts.getOrNull(1)?.toIntOrNull() ?: -1
                        val credParts = lines[1].split("\t", limit = 2)
                        val email = credParts.getOrNull(0) ?: ""
                        val password = credParts.getOrNull(1) ?: ""
                        val cookies = lines[2]
                        Log.d(TAG, "Received auth response (ttl=$ttl idx=$idx creds=${email.isNotBlank()} cookies=${cookies.length} chars) from ${recvPacket.address}")
                        return LanAuthPayload(ttl, idx, email, password, cookies)
                    } else {
                        Log.d(TAG, "Malformed auth response, ignoring")
                    }
                } else {
                    Log.d(TAG, "Ignoring non-response packet (${data.take(20)}...)")
                }
            }

            Log.d(TAG, "Discovery timed out after ${timeoutMs}ms")
            sock.close()
            requesterSocket = null
            null
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "Discovery timed out after ${timeoutMs}ms")
            requesterSocket?.close()
            requesterSocket = null
            null
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed: ${e.message}", e)
            requesterSocket?.close()
            requesterSocket = null
            null
        }
    }

    /** Cancel an in-progress discovery (closes the socket, causing receive() to throw). */
    fun cancelDiscovery() {
        requesterSocket?.close()
        requesterSocket = null
    }

    // ── Listener side ───────────────────────────────────────────────

    private var listenerJob: Job? = null
    private var listenerSocket: DatagramSocket? = null

    /**
     * Start listening for auth discovery broadcasts.
     *
     * @param port UDP port to listen on
     * @param scope coroutine scope (typically lifecycleScope)
     * @param onRequest called on Main dispatcher with sender IP and device name
     */
    fun startListening(
        port: Int,
        scope: CoroutineScope,
        onRequest: (senderIp: InetAddress, deviceName: String) -> Unit
    ) {
        if (listenerJob?.isActive == true) return  // already listening

        listenerJob = scope.launch(Dispatchers.IO) {
            try {
                val sock = DatagramSocket(port).also { listenerSocket = it }
                sock.broadcast = true
                Log.d(TAG, "Listening for auth requests on port $port")

                while (isActive) {
                    val buf = ByteArray(MAX_PACKET)
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(packet)
                    } catch (e: Exception) {
                        if (!isActive) break  // socket closed by stopListening
                        throw e
                    }

                    val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (data.startsWith(MAGIC_REQ)) {
                        val deviceName = data.removePrefix(MAGIC_REQ).trimStart('\n').take(100)
                        val senderIp = packet.address
                        Log.d(TAG, "Auth request from $senderIp ($deviceName)")
                        withContext(Dispatchers.Main) {
                            onRequest(senderIp, deviceName)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Listener error: ${e.message}", e)
                }
            } finally {
                listenerSocket?.close()
                listenerSocket = null
                Log.d(TAG, "Listener stopped")
            }
        }
    }

    /**
     * Send session (DPI strategy + cookies) to a specific device (unicast reply).
     * Call from IO dispatcher.
     */
    fun sendSession(target: InetAddress, port: Int, dpiTtl: Int, strategyIndex: Int, email: String, password: String, cookieString: String) {
        try {
            val payload = "$MAGIC_RESP\n$dpiTtl:$strategyIndex\n$email\t$password\n$cookieString".toByteArray(Charsets.UTF_8)
            val sock = DatagramSocket()
            val packet = DatagramPacket(payload, payload.size, target, port)
            sock.send(packet)
            sock.close()
            Log.d(TAG, "Sent session (ttl=$dpiTtl idx=$strategyIndex creds=${email.isNotBlank()} cookies=${cookieString.length} chars) to $target:$port")
        } catch (e: Exception) {
            Log.e(TAG, "sendSession failed: ${e.message}", e)
        }
    }

    /** Stop the listener loop. */
    fun stopListening() {
        listenerSocket?.close()
        listenerJob?.cancel()
        listenerJob = null
        listenerSocket = null
    }
}
