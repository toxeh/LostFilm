package ru.twicker.lostfilmtv.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "CiadpiTuner"

/**
 * Data class representing a ciadpi DPI bypass strategy.
 * @param name Human-readable name
 * @param args Command-line arguments for ciadpi (excluding --port)
 */
data class CiadpiStrategy(
    val name: String,
    val args: List<String>
)

/**
 * Two-phase automatic DPI bypass strategy tuner.
 *
 * Phase 1: TTL Probing — determine the TTL distance to DPI (ТСПУ)
 *   - Try `--fake -1 --ttl N` for N = 1..MAX_TTL
 *   - Test each by downloading a test URL AND a test image
 *   - Smallest N where both succeed = TTL to DPI device
 *
 * Phase 2: Combo strategies — test combinations with the found TTL
 *   - fake only, fake+split, disorder+fake
 *   - Pick the fastest
 *
 * If TTL probing fails, fall back to basic strategies (tlsrec, split, oob).
 */
object CiadpiTuner {

    private const val PREFS_NAME = "ciadpi_tuner"
    private const val KEY_STRATEGY_INDEX = "best_strategy_index"
    private const val KEY_DPI_TTL = "dpi_ttl"
    private const val TEST_URL = "https://www.lostfilm.tv/new"
    // Image (~340KB) for body-download test through DPI
    private const val TEST_IMAGE_URL = "https://www.lostfilm.tv/Static/Images/362/Posters/e_9_14.jpg"
    private const val MAX_TTL = 12

    /**
     * Build full strategy list: TTL-based strategies first (if TTL known),
     * then fallback basic strategies.
     */
    fun buildStrategies(dpiTtl: Int = -1): List<CiadpiStrategy> = buildList {
        if (dpiTtl > 0) {
            // TTL-based strategies (best for full body downloads)
            add(CiadpiStrategy("fake ttl$dpiTtl", listOf("--fake", "-1", "--ttl", "$dpiTtl")))
            add(CiadpiStrategy("fake ttl$dpiTtl +split", listOf("--fake", "-1", "--ttl", "$dpiTtl", "--split", "1+s")))
            add(CiadpiStrategy("disorder+fake ttl$dpiTtl", listOf("--disorder", "1", "--fake", "-1", "--ttl", "$dpiTtl")))
            // Try TTL+1 in case of marginal hops
            if (dpiTtl > 1) {
                add(CiadpiStrategy("fake ttl${dpiTtl - 1}", listOf("--fake", "-1", "--ttl", "${dpiTtl - 1}")))
            }
        }
        // Fallback basic strategies
        add(CiadpiStrategy("tlsrec 1+s", listOf("--tlsrec", "1+s")))
        add(CiadpiStrategy("split 1+s", listOf("--split", "1+s")))
        add(CiadpiStrategy("disorder 1+s", listOf("--disorder", "1+s")))
        add(CiadpiStrategy("oob 1+s", listOf("--oob", "1+s")))
    }

    /** Get strategy args with --timeout 600 (10min) for production use */
    fun getProductionArgs(strategy: CiadpiStrategy): List<String> =
        strategy.args + listOf("--timeout", "600")

    /** Get strategy args with --timeout 8 for quick benchmarking */
    private fun getBenchmarkArgs(strategy: CiadpiStrategy): List<String> =
        strategy.args + listOf("--timeout", "8")

    fun hasSavedStrategy(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.contains(KEY_STRATEGY_INDEX) && prefs.contains(KEY_DPI_TTL)
    }

    fun getSavedStrategy(context: Context): CiadpiStrategy? {
        val prefs = getPrefs(context)
        val ttl = prefs.getInt(KEY_DPI_TTL, -1)
        val idx = prefs.getInt(KEY_STRATEGY_INDEX, -1)
        val strats = buildStrategies(ttl)
        return if (idx in strats.indices) strats[idx] else null
    }

    fun getSavedTtl(context: Context): Int =
        getPrefs(context).getInt(KEY_DPI_TTL, -1)

    fun clearSavedStrategy(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_STRATEGY_INDEX)
            .remove(KEY_DPI_TTL)
            .apply()
    }

    /**
     * Verify saved strategy by downloading a test image (340KB).
     * Must be called after ciadpi is started.
     * @return true if image downloaded OK, false if strategy is broken
     */
    fun verifyStrategy(): Boolean {
        Log.i(TAG, "Verifying strategy: downloading test image...")
        val elapsed = benchmarkDownload(TEST_IMAGE_URL, 500)
        if (elapsed > 0) {
            Log.i(TAG, "Strategy verified OK: image downloaded in ${elapsed}ms")
            return true
        }
        Log.w(TAG, "Strategy verification FAILED: image download failed")
        return false
    }

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Two-phase tuning: TTL probe → combo benchmark → pick best.
     * @param onProgress callback(stepDescription, currentStep, totalSteps)
     * @return index of the best strategy in buildStrategies(ttl), or -1
     */
    suspend fun tune(
        context: Context,
        onProgress: ((name: String, index: Int, total: Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== Starting two-phase DPI tuning ===")

        // Phase 1: TTL probing
        val totalPhase1Steps = MAX_TTL
        val dpiTtl = probeTtl(context, onProgress, totalPhase1Steps)

        if (dpiTtl > 0) {
            Log.i(TAG, "Phase 1 complete: DPI TTL = $dpiTtl")
        } else {
            Log.w(TAG, "Phase 1: TTL probing failed, will use fallback strategies")
        }

        // Phase 2: benchmark combo strategies
        val strategies = buildStrategies(dpiTtl)
        val totalPhase2Steps = strategies.size
        val phase2Offset = totalPhase1Steps

        var bestIndex = -1
        var bestTime = Long.MAX_VALUE

        for ((index, strategy) in strategies.withIndex()) {
            val step = phase2Offset + index
            val total = phase2Offset + totalPhase2Steps
            onProgress?.invoke("Тест: ${strategy.name}", step, total)
            Log.d(TAG, "Phase 2: testing #$index ${strategy.name}")

            CiadpiRunner.stop()
            Thread.sleep(300)

            val started = CiadpiRunner.start(context, getBenchmarkArgs(strategy))
            if (!started) {
                Log.w(TAG, "Phase 2: #$index (${strategy.name}) — ciadpi failed to start")
                continue
            }

            // Benchmark: download HTML + image to verify full body works
            val htmlTime = benchmarkDownload(TEST_URL, 1000)
            val imgTime = if (htmlTime > 0) benchmarkDownload(TEST_IMAGE_URL, 500) else -1L
            CiadpiRunner.stop()

            if (htmlTime > 0 && imgTime > 0) {
                val total_ms = htmlTime + imgTime
                Log.i(TAG, "Phase 2: #$index (${strategy.name}) — html=${htmlTime}ms img=${imgTime}ms total=${total_ms}ms")
                if (total_ms < bestTime) {
                    bestTime = total_ms
                    bestIndex = index
                }
            } else {
                Log.w(TAG, "Phase 2: #$index (${strategy.name}) — failed (html=$htmlTime img=$imgTime)")
            }
        }

        // Save and start winner
        val prefs = getPrefs(context)
        prefs.edit()
            .putInt(KEY_DPI_TTL, dpiTtl)
            .putInt(KEY_STRATEGY_INDEX, bestIndex)
            .apply()

        CiadpiRunner.stop()
        Thread.sleep(300)

        if (bestIndex >= 0) {
            val winner = strategies[bestIndex]
            Log.i(TAG, "=== Winner: #$bestIndex (${winner.name}) — ${bestTime}ms, TTL=$dpiTtl ===")
            CiadpiRunner.start(context, getProductionArgs(winner))
        } else {
            // Ultimate fallback
            val fallback = CiadpiStrategy("tlsrec fallback", listOf("--tlsrec", "1+s"))
            Log.e(TAG, "=== No strategy worked! Using fallback: ${fallback.name} ===")
            CiadpiRunner.start(context, getProductionArgs(fallback))
        }

        bestIndex
    }

    /**
     * Phase 1: Probe TTL to DPI device.
     * Try --fake -1 --ttl N for N=1..MAX_TTL.
     * Return smallest N where download succeeds, or -1.
     */
    private fun probeTtl(
        context: Context,
        onProgress: ((String, Int, Int) -> Unit)?,
        totalSteps: Int
    ): Int {
        Log.i(TAG, "Phase 1: TTL probing (1..$MAX_TTL)")

        for (ttl in 1..MAX_TTL) {
            onProgress?.invoke("TTL probe: $ttl/$MAX_TTL", ttl - 1, totalSteps + 8)
            Log.d(TAG, "Phase 1: trying TTL=$ttl")

            CiadpiRunner.stop()
            Thread.sleep(300)

            val args = listOf("--fake", "-1", "--ttl", "$ttl", "--timeout", "8")
            val started = CiadpiRunner.start(context, args)
            if (!started) {
                Log.w(TAG, "Phase 1: TTL=$ttl — ciadpi failed to start")
                continue
            }

            // Test HTML first (quick check)
            val htmlOk = benchmarkDownload(TEST_URL, 1000)
            if (htmlOk < 0) {
                Log.d(TAG, "Phase 1: TTL=$ttl — HTML failed")
                CiadpiRunner.stop()
                continue
            }

            // Test image download (body check)
            val imgOk = benchmarkDownload(TEST_IMAGE_URL, 500)
            CiadpiRunner.stop()

            if (imgOk > 0) {
                Log.i(TAG, "Phase 1: TTL=$ttl — SUCCESS! HTML=${htmlOk}ms, IMG=${imgOk}ms")
                return ttl
            } else {
                Log.d(TAG, "Phase 1: TTL=$ttl — HTML ok but image failed")
            }
        }

        return -1
    }

    /**
     * Download a URL through current ciadpi instance.
     * @param url URL to download
     * @param minBodyLength minimum body length to consider success
     * @return elapsed time in ms, or -1 on failure
     */
    private fun benchmarkDownload(url: String, minBodyLength: Int): Long {
        val client = try {
            NetworkClient.createCiadpiClient().newBuilder()
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ciadpi client: ${e.message}")
            return -1
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkClient.UA)
            .build()

        val start = System.currentTimeMillis()
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.bytes()
            val elapsed = System.currentTimeMillis() - start
            response.close()

            if (response.isSuccessful && body != null && body.size > minBodyLength) {
                elapsed
            } else {
                Log.w(TAG, "Benchmark $url: HTTP ${response.code}, body=${body?.size ?: 0}")
                -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Benchmark $url error: ${e.message}")
            -1
        }
    }
}
