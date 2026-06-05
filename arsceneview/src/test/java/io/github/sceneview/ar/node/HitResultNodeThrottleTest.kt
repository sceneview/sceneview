package io.github.sceneview.ar.node

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the opt-in hit-test rate-limit of [HitResultNode] (#2328 / #2402 MED-5).
 *
 * `HitResultNode.update` previously called ARCore's [com.google.ar.core.Frame.hitTest] — a
 * comparatively expensive per-pixel raycast — on **every** updated frame with no rate-limit.
 * [hitResultRefreshThrottled] adds an opt-in throttle mirroring [pointCloudRebuildThrottled].
 *
 * The behaviour-preservation contract proven here:
 *  - the DEFAULT `refreshIntervalMs = 0` never throttles → the hit test still runs every frame,
 *    byte-for-byte as before (the strict behaviour-preservation guarantee for this change);
 *  - a positive interval skips hit tests inside the window and re-fires once it elapses;
 *  - the `lastHitTest = 0L` first-frame value never blocks the first hit test (the #2186 guard —
 *    `0L`, not `Long.MIN_VALUE`).
 *
 * Pure Kotlin — no ARCore / Filament, matching the [pointCloudRebuildThrottled] extract-and-pin
 * pattern.
 */
class HitResultNodeThrottleTest {

    @Test
    fun `default zero interval never throttles — hit tests every frame`() {
        assertFalse(
            "refreshIntervalMs = 0 is the 'every frame' sentinel and must never throttle",
            hitResultRefreshThrottled(
                now = 1_000L,
                lastHitTestTimestampMs = 999L,
                refreshIntervalMs = 0L,
            ),
        )
        assertFalse(
            "Even back-to-back frames with the default interval must run the hit test",
            hitResultRefreshThrottled(
                now = 1_000L,
                lastHitTestTimestampMs = 1_000L,
                refreshIntervalMs = 0L,
            ),
        )
    }

    @Test
    fun `positive interval skips inside the window`() {
        assertTrue(
            "A hit test 100 ms after the last one with a 200 ms interval must be skipped",
            hitResultRefreshThrottled(
                now = 1_100L,
                lastHitTestTimestampMs = 1_000L,
                refreshIntervalMs = 200L,
            ),
        )
    }

    @Test
    fun `positive interval runs once the window elapses`() {
        assertFalse(
            "A hit test exactly at the interval boundary must fire",
            hitResultRefreshThrottled(
                now = 1_200L,
                lastHitTestTimestampMs = 1_000L,
                refreshIntervalMs = 200L,
            ),
        )
        assertFalse(
            "A hit test past the interval must fire",
            hitResultRefreshThrottled(
                now = 1_500L,
                lastHitTestTimestampMs = 1_000L,
                refreshIntervalMs = 200L,
            ),
        )
    }

    @Test
    fun `first frame is never blocked by the 0L last-hit-test sentinel`() {
        // lastHitTest = 0L means "never run". With a positive interval the first real frame (a
        // realistic positive clock reading) must NOT be throttled — the #2186 overflow guard would
        // have wrongly blocked it had the sentinel been Long.MIN_VALUE.
        assertFalse(
            "The first hit test must fire even with a positive refresh interval (#2186)",
            hitResultRefreshThrottled(
                now = 1_000L,
                lastHitTestTimestampMs = 0L,
                refreshIntervalMs = 200L,
            ),
        )
    }
}
