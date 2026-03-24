package ru.twicker.lostfilmtv.network

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for CiadpiTuner — strategy building, args generation, data class behaviour.
 * Network-dependent methods (tune, verifyStrategy, benchmarkDownload) are tested on-device.
 */
class CiadpiTunerTest {

    // ---- CiadpiStrategy data class ----

    @Test
    fun `CiadpiStrategy equality by name and args`() {
        val a = CiadpiStrategy("fake ttl6", listOf("--fake", "-1", "--ttl", "6"))
        val b = CiadpiStrategy("fake ttl6", listOf("--fake", "-1", "--ttl", "6"))
        assertEquals(a, b)
    }

    @Test
    fun `CiadpiStrategy different args are not equal`() {
        val a = CiadpiStrategy("fake ttl6", listOf("--fake", "-1", "--ttl", "6"))
        val b = CiadpiStrategy("fake ttl7", listOf("--fake", "-1", "--ttl", "7"))
        assertNotEquals(a, b)
    }

    // ---- buildStrategies ----

    @Test
    fun `buildStrategies without TTL returns only fallback strategies`() {
        val strats = CiadpiTuner.buildStrategies(-1)
        // Should contain only basic strategies (no fake ttl)
        assertTrue("Should have at least 4 fallback strategies", strats.size >= 4)
        assertTrue("All should be fallback", strats.all { !it.name.startsWith("fake") })
        assertEquals("tlsrec 1+s", strats[0].name)
        assertEquals("oob 1+s", strats.last().name)
    }

    @Test
    fun `buildStrategies with TTL 6 includes fake strategies first`() {
        val strats = CiadpiTuner.buildStrategies(6)
        // First strategies should be TTL-based
        assertTrue("First should be fake ttl6", strats[0].name.contains("fake ttl6"))
        assertTrue("Should include fake+split", strats.any { it.name.contains("+split") })
        assertTrue("Should include disorder+fake", strats.any { it.name.contains("disorder+fake") })
        // Should also include fallback strategies
        assertTrue("Should include tlsrec fallback", strats.any { it.name == "tlsrec 1+s" })
        assertTrue("Should include oob fallback", strats.any { it.name == "oob 1+s" })
    }

    @Test
    fun `buildStrategies with TTL 6 includes ttl-1 variant`() {
        val strats = CiadpiTuner.buildStrategies(6)
        assertTrue("Should include fake ttl5 (ttl-1)", strats.any { it.name == "fake ttl5" })
    }

    @Test
    fun `buildStrategies with TTL 1 does not include ttl-1 variant`() {
        val strats = CiadpiTuner.buildStrategies(1)
        assertFalse("Should NOT include fake ttl0", strats.any { it.name == "fake ttl0" })
    }

    @Test
    fun `buildStrategies with TTL 2 includes ttl-1 variant`() {
        val strats = CiadpiTuner.buildStrategies(2)
        assertTrue("Should include fake ttl1 (ttl-1)", strats.any { it.name == "fake ttl1" })
    }

    // ---- getProductionArgs ----

    @Test
    fun `getProductionArgs appends timeout 600`() {
        val strategy = CiadpiStrategy("fake ttl6", listOf("--fake", "-1", "--ttl", "6"))
        val args = CiadpiTuner.getProductionArgs(strategy)
        assertEquals(
            listOf("--fake", "-1", "--ttl", "6", "--timeout", "600"),
            args
        )
    }

    @Test
    fun `getProductionArgs for basic strategy`() {
        val strategy = CiadpiStrategy("tlsrec 1+s", listOf("--tlsrec", "1+s"))
        val args = CiadpiTuner.getProductionArgs(strategy)
        assertEquals(listOf("--tlsrec", "1+s", "--timeout", "600"), args)
    }

    // ---- Strategy arg correctness ----

    @Test
    fun `fake ttl strategy has correct args`() {
        val strats = CiadpiTuner.buildStrategies(8)
        val fake = strats.first { it.name == "fake ttl8" }
        assertEquals(listOf("--fake", "-1", "--ttl", "8"), fake.args)
    }

    @Test
    fun `fake ttl+split strategy has correct args`() {
        val strats = CiadpiTuner.buildStrategies(8)
        val fakeSplit = strats.first { it.name == "fake ttl8 +split" }
        assertEquals(listOf("--fake", "-1", "--ttl", "8", "--split", "1+s"), fakeSplit.args)
    }

    @Test
    fun `disorder+fake strategy has correct args`() {
        val strats = CiadpiTuner.buildStrategies(8)
        val disorderFake = strats.first { it.name == "disorder+fake ttl8" }
        assertEquals(listOf("--disorder", "1", "--fake", "-1", "--ttl", "8"), disorderFake.args)
    }

    // ---- Strategy count ----

    @Test
    fun `buildStrategies with TTL returns TTL strategies plus fallbacks`() {
        val withTtl = CiadpiTuner.buildStrategies(6)
        val without = CiadpiTuner.buildStrategies(-1)
        assertTrue("TTL strategies should add extra entries",
            withTtl.size > without.size)
    }

    @Test
    fun `buildStrategies without TTL has 4 fallback strategies`() {
        val strats = CiadpiTuner.buildStrategies(-1)
        assertEquals(4, strats.size)
        assertEquals(listOf("tlsrec 1+s", "split 1+s", "disorder 1+s", "oob 1+s"),
            strats.map { it.name })
    }

    @Test
    fun `buildStrategies with TTL 6 has 8 total strategies`() {
        // 4 TTL-based (fake, fake+split, disorder+fake, fake ttl5) + 4 fallback
        val strats = CiadpiTuner.buildStrategies(6)
        assertEquals(8, strats.size)
    }
}
