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
            val started = tryStart(binFile, cmd)
            if (started) return true

            // Fallback: copy to filesDir (some Android TV SELinux policies block exec from nativeLibraryDir)
            val fallbackBin = File(context.filesDir, "ciadpi")
            binFile.copyTo(fallbackBin, overwrite = true)
            fallbackBin.setExecutable(true)
            Log.i(TAG, "Trying fallback exec from ${fallbackBin.absolutePath}")
            val fallbackCmd = mutableListOf(fallbackBin.absolutePath, "--port", "$port")
            fallbackCmd.addAll(strategyArgs)
            val started2 = tryStart(fallbackBin, fallbackCmd)
            if (started2) return true

            // Native binary completely blocked (seccomp) → fall back to pure Java proxy
            Log.w(TAG, "Native ciadpi unavailable, starting JavaDpiProxy")
            val javaOk = JavaDpiProxy.start(NetworkClient.doHDns)
            if (javaOk) {
                port = JavaDpiProxy.port
                Log.i(TAG, "JavaDpiProxy SOCKS5 ready on port $port")
                return true
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ciadpi: ${e.message}", e)
            false
        }
    }

    private fun tryStart(binFile: File, cmd: List<String>): Boolean {
        stop()
        return try {
            val pb = ProcessBuilder(cmd)
                .redirectErrorStream(true)
            process = pb.start()

            // Drain stdout/stderr in background to prevent blocking
            Thread {
                try { process?.inputStream?.bufferedReader()?.forEachLine { Log.d(TAG, "ciadpi: $it") } }
                catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            // Wait for the port to open (up to 8 seconds for slow devices)
            val ready = waitForPort(port, timeoutMs = 8000)
            if (ready) {
                Log.i(TAG, "ciadpi SOCKS5 ready on port $port")
            } else {
                // Check if process died
                val alive = try { process?.exitValue(); false } catch (_: IllegalThreadStateException) { true }
                val exitCode = try { process?.exitValue() } catch (_: Exception) { null }
                Log.e(TAG, "ciadpi did not open port $port in time (alive=$alive exitCode=$exitCode bin=${binFile.absolutePath})")
                stop()
            }
            ready
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ciadpi from ${binFile.absolutePath}: ${e.message}", e)
            false
        }
    }

    fun stop() {
        process?.let {
            Log.d(TAG, "Stopping ciadpi process")
            it.destroyForcibly()
        }
        process = null
        JavaDpiProxy.stop()
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
