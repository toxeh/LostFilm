package ru.twicker.lostfilmtv.network

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

private const val TAG = "CiadpiRunner"

/**
 * Runs the embedded ciadpi binary (jniLibs/arm64-v8a/libciadpi.so)
 * as a local SOCKS5 proxy on 127.0.0.1:10800.
 * Android installs .so files with execute permission automatically.
 */
object CiadpiRunner {

    var port: Int = 0
        private set
    private var process: Process? = null

    val proxy: Proxy get() = Proxy(
        Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)
    )

    private fun findFreePort(): Int =
        java.net.ServerSocket(0).use { it.localPort }

    /**
     * Start the embedded ciadpi from nativeLibraryDir (always executable on Android).
     * Returns true if started and port 10800 is reachable.
     */
    fun start(context: Context, strategyArgs: List<String> = listOf("--tlsrec", "1+s", "--timeout", "8")): Boolean {
        stop()

        // Android installs .so files from jniLibs into nativeLibraryDir with +x
        val binFile = File(context.applicationInfo.nativeLibraryDir, "libciadpi.so")
        if (!binFile.exists()) {
            Log.e(TAG, "ciadpi binary not found at ${binFile.absolutePath}")
            return false
        }
        Log.i(TAG, "Found ciadpi at ${binFile.absolutePath} (${binFile.length()} bytes)")

        // Use provided strategy args or default
        port = findFreePort()
        val cmd = mutableListOf(binFile.absolutePath, "--port", "$port")
        cmd.addAll(strategyArgs)

        Log.i(TAG, "Starting: ${cmd.joinToString(" ")}")

        return try {
            val pb = ProcessBuilder(cmd)
                .redirectErrorStream(true)
            process = pb.start()

            // Drain stdout/stderr in background to prevent blocking
            Thread {
                try { process?.inputStream?.bufferedReader()?.forEachLine { Log.d(TAG, "ciadpi: $it") } }
                catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            // Wait for the port to open (up to 3 seconds)
            val ready = waitForPort(port, timeoutMs = 3000)
            if (ready) {
                Log.i(TAG, "ciadpi SOCKS5 ready on port $port")
            } else {
                Log.e(TAG, "ciadpi did not open port $port in time")
                stop()
            }
            ready
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ciadpi: ${e.message}", e)
            false
        }
    }

    fun stop() {
        process?.let {
            Log.d(TAG, "Stopping ciadpi process")
            it.destroyForcibly()
        }
        process = null
    }

    fun isRunning(): Boolean {
        val p = process ?: return false
        return try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }
    }


    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 200)
                    return true
                }
            } catch (_: Exception) {
                Thread.sleep(200)
            }
        }
        return false
    }
}
