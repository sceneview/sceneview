package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlacementFallbackTest {
    private val state = AutoPlacementState()
    private val attempts = mutableListOf<PlacementSource>()

    private fun frame(
        now: Long,
        surface: Boolean = false,
        estimated: Boolean = true,
        tracking: Boolean = true,
        settled: Boolean? = null,
        result: ((PlacementSource) -> PlacementAttempt)? = null,
    ): FrameEffect =
        state.onFrame(FrameInput(now, tracking, surface, anchorTracking = settled?.let { true }), estimated, settled) {
            attempts += it
            result?.invoke(it) ?: when (it) {
                PlacementSource.SURFACE -> PlacementAttempt.SETTLED
                PlacementSource.ESTIMATED -> PlacementAttempt.APPROXIMATE
            }
        }

    @Test
    fun `the default estimate is eligible at 600 milliseconds`() {
        assertEquals(600L, state.estimatedFallbackMillis)
        state.requestPlacement()
        assertEquals(FrameEffect.NONE, frame(20L))
        assertEquals(FrameEffect.NONE, frame(619L))
        assertTrue(attempts.isEmpty())
        assertEquals(FrameEffect.PLACE, frame(620L))
        assertEquals(listOf(PlacementSource.ESTIMATED), attempts)
        assertTrue(state.isEstimated)
        assertFalse(state.isSettled)
        assertEquals(1, state.placementsCreated)
    }

    @Test
    fun `a usable surface has priority when the estimate is due`() {
        state.requestPlacement()
        frame(0L)
        assertEquals(FrameEffect.PLACE, frame(600L, surface = true))
        assertEquals(listOf(PlacementSource.SURFACE), attempts)
        assertFalse(state.isEstimated)
        assertTrue(state.isSettled)
    }

    @Test
    fun `a null fallback waits for a surface`() {
        state.estimatedFallbackMillis = null
        state.requestPlacement()
        frame(0L)
        assertEquals(FrameEffect.NONE, frame(15_000L))
        assertTrue(attempts.isEmpty())
        assertEquals(FrameEffect.PLACE, frame(15_001L, surface = true))
        assertFalse(state.isEstimated)
    }

    @Test
    fun `a wall flow offers no estimate`() {
        state.requestPlacement()
        frame(0L, estimated = false)
        assertEquals(FrameEffect.NONE, frame(600L, estimated = false))
        assertTrue(attempts.isEmpty())
        assertEquals(FrameEffect.PLACE, frame(900L, surface = true, estimated = false))
    }

    @Test
    fun `only frames received while tracking count toward the estimate`() {
        state.requestPlacement()
        frame(0L, tracking = false)
        frame(5_000L)
        frame(5_500L, tracking = false)
        frame(10_000L)
        assertEquals(FrameEffect.NONE, frame(10_599L))
        assertEquals(FrameEffect.PLACE, frame(10_600L))
    }

    @Test
    fun `the reset starts a fresh estimate timer on the next tracked frame`() {
        state.resetPlacement(0L)
        assertEquals(FrameEffect.NONE, frame(900L))
        assertEquals(FrameEffect.NONE, frame(1_499L))
        assertEquals(FrameEffect.PLACE, frame(1_500L))
    }

    @Test
    fun `full tracking settles the estimated point without a second placement`() {
        state.requestPlacement()
        frame(0L)
        frame(600L)
        frame(700L, settled = false)
        assertFalse(state.isSettled)
        frame(800L, settled = true)
        frame(900L, settled = false)
        assertTrue(state.isSettled)
        assertTrue(state.isEstimated)
        assertEquals(1, state.placementsCreated)
        state.resetPlacement(1_000L)
        assertFalse(state.isEstimated)
        assertFalse(state.isSettled)
    }

    @Test
    fun `retry waits after anchor failure and keeps the placement request alive`() {
        state.requestPlacement()
        val fail: (PlacementSource) -> PlacementAttempt = { PlacementAttempt.FAILED }
        assertEquals(FrameEffect.NONE, frame(0L, surface = true, result = fail))
        assertEquals(FrameEffect.NONE, frame(99L, surface = true, result = fail))
        assertEquals(FrameEffect.NONE, frame(100L, surface = true, result = fail))
        assertEquals(FrameEffect.NONE, frame(299L, surface = true, result = fail))
        assertEquals(2, attempts.size)
        assertTrue(state.placementRequested)
        assertEquals(FrameEffect.PLACE, frame(300L, surface = true))
    }

    @Test
    fun `moving from an estimate onto a plane updates the source without consuming a new request`() {
        state.requestPlacement()
        frame(0L)
        frame(600L)
        state.onPlacementAnchor(PlacementSource.SURFACE, settled = true)
        assertFalse(state.isEstimated)
        assertTrue(state.isSettled)
        assertEquals(1, state.placementsCreated)
    }

    @Test
    fun `a new plane can win while an estimated anchor cools down`() {
        state.requestPlacement()
        frame(0L)
        frame(600L) { PlacementAttempt.FAILED }
        frame(616L)
        assertEquals(listOf(PlacementSource.ESTIMATED), attempts)
        assertEquals(FrameEffect.PLACE, frame(632L, surface = true))
        assertEquals(listOf(PlacementSource.ESTIMATED, PlacementSource.SURFACE), attempts)
    }

    @Test
    fun `no instant point is not an anchor failure`() {
        state.requestPlacement()
        frame(0L)
        frame(600L) { PlacementAttempt.UNAVAILABLE }
        assertEquals(FrameEffect.PLACE, frame(616L))
        assertEquals(listOf(PlacementSource.ESTIMATED, PlacementSource.ESTIMATED), attempts)
    }

    @Test
    fun `recenter needs a placement and a tracked camera`() {
        val request = RecenterRequest(PlacementSurface.SURFACE)
        state.recenterAction = request::request
        request.onCameraTracking(true)
        assertFalse(state.recenter())
        state.requestPlacement()
        frame(0L, surface = true)
        assertTrue(state.recenter())

        request.onCameraTracking(false)
        assertFalse(state.recenter())
        request.onFrame(16L, ready = true) { error("Tracking loss must cancel the request") }
        frame(16L, tracking = false)
        request.onCameraTracking(true)
        assertFalse(state.recenter())
    }

    @Test
    fun `recenter refuses a placed wall even with a tracked camera`() {
        val request = RecenterRequest(PlacementSurface.WALL)
        request.onCameraTracking(true)
        state.recenterAction = request::request
        state.requestPlacement()
        frame(0L, surface = true, estimated = false)
        assertTrue(state.hasPlacement)
        assertFalse(state.recenter())
    }

    @Test
    fun `recenter waits for the gesture to end and retries a refused anchor`() {
        val request = RecenterRequest(PlacementSurface.SURFACE)
        request.onCameraTracking(true)
        state.recenterAction = request::request
        state.requestPlacement()
        frame(0L, surface = true)
        assertTrue(state.beginAdjustment())
        assertTrue(state.recenter())
        request.onFrame(16L, ready = false) { error("An active gesture defers recentering") }
        state.endAdjustment()

        var moves = 0
        request.onFrame(32L, ready = true) { moves++; false }
        request.onFrame(131L, ready = true) { error("The failed anchor is still in back-off") }
        request.onFrame(132L, ready = true) { moves++; true }
        request.onFrame(250L, ready = true) { error("A successful recenter consumes the request") }
        assertEquals(2, moves)
        assertEquals(1, state.placementsCreated)
    }
}
