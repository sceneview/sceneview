package io.github.sceneview.ar.node

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the AR8 (#2263) opt-in rate-limit of [PointCloudNode].
 *
 * `PointCloudNode.update` previously rebuilt the cloud — allocating a `FloatArray` of positions
 * plus two direct `ByteBuffer`s for the Filament upload — on **every** tracked frame, with no
 * rate-limit (unlike [DepthMeshNode]). [pointCloudRebuildThrottled] adds an opt-in throttle.
 *
 * The behaviour-preservation contract proven here:
 *  - the DEFAULT `refreshIntervalMs = 0` never throttles → the cloud still rebuilds every frame,
 *    byte-for-byte as before (the strict behaviour-preservation guarantee for this batch);
 *  - a positive interval skips rebuilds inside the window and re-fires once it elapses;
 *  - the `lastRebuild = 0L` first-frame value never blocks the first rebuild (the #2186 guard —
 *    `0L`, not `Long.MIN_VALUE`).
 *
 * Pure Kotlin — no ARCore / Filament, matching the `buildPointCloudPositions` extract-and-pin
 * pattern.
 */
class PointCloudNodeThrottleTest {

    @Test
    fun `default zero interval never throttles — rebuilds every frame`() {
        // Default DEFAULT_REFRESH_INTERVAL_MS is the per-frame sentinel.
        assertFalse(
            "refreshIntervalMs = 0 is the 'every frame' sentinel and must never throttle",
            pointCloudRebuildThrottled(
                now = 1_000L,
                lastRebuildTimestampMs = 999L,
                refreshIntervalMs = 0L,
            ),
        )
        assertFalse(
            "Even back-to-back frames with the default interval must rebuild",
            pointCloudRebuildThrottled(
                now = 1_000L,
                lastRebuildTimestampMs = 1_000L,
                refreshIntervalMs = 0L,
            ),
        )
    }

    @Test
    fun `default constant is the per-frame sentinel`() {
        assertFalse(
            "DEFAULT_REFRESH_INTERVAL_MS must be the 0 sentinel so the default behaviour is " +
                "unchanged per-frame rebuilds",
            pointCloudRebuildThrottled(
                now = 5_000L,
                lastRebuildTimestampMs = 4_999L,
                refreshIntervalMs = PointCloudNode.DEFAULT_REFRESH_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun `positive interval skips inside the window`() {
        assertTrue(
            "A rebuild 100 ms after the last one with a 200 ms interval must be skipped",
            pointCloudRebuildThrottled(
                now = 1_100L,
                lastRebuildTimestampMs = 1_000L,
                refreshIntervalMs = 200L,
            ),
        )
    }

    @Test
    fun `positive interval rebuilds once the window elapses`() {
        assertFalse(
            "A rebuild exactly at the interval boundary must fire",
            pointCloudRebuildThrottled(
                now = 1_200L,
                lastRebuildTimestampMs = 1_000L,
                refreshIntervalMs = 200L,
            ),
        )
        assertFalse(
            "A rebuild past the interval must fire",
            pointCloudRebuildThrottled(
                now = 1_500L,
                lastRebuildTimestampMs = 1_000L,
                refreshIntervalMs = 200L,
            ),
        )
    }

    @Test
    fun `first frame is never blocked by the 0L last-rebuild sentinel`() {
        // lastRebuild = 0L means "never rebuilt". With a positive interval the first real frame
        // (a realistic positive clock reading) must NOT be throttled — the #2186 overflow guard
        // would have wrongly blocked it had the sentinel been Long.MIN_VALUE.
        assertFalse(
            "The first rebuild must fire even with a positive refresh interval (#2186)",
            pointCloudRebuildThrottled(
                now = 1_000L,
                lastRebuildTimestampMs = 0L,
                refreshIntervalMs = 200L,
            ),
        )
    }
}
