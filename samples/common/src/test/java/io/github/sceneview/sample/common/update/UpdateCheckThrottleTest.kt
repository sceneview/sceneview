package io.github.sceneview.sample.common.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The once-per-interval rule behind [InAppUpdateManager.checkForUpdate] (#3939). Plain JVM. */
class UpdateCheckThrottleTest {

    private var now = 1_000L
    private val throttle = UpdateCheckThrottle(minIntervalMillis = 100L, clock = { now })

    @Test
    fun `the first check of a process always runs`() {
        assertTrue(throttle.shouldCheck())
    }

    @Test
    fun `a quiet answer skips checks until the interval has passed`() {
        throttle.recordQuietResult()

        now += 99
        assertFalse(throttle.shouldCheck())

        now += 1
        assertTrue(throttle.shouldCheck())
    }

    @Test
    fun `an answer that shows a flow never throttles the next check`() {
        throttle.recordQuietResult()
        throttle.recordFlowResult()

        assertTrue(throttle.shouldCheck())
    }
}
