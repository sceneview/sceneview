package io.github.sceneview.demo.demos.internal

/**
 * Pure feedback gate for `ARPointCloudDemo` (#3270).
 *
 * [io.github.sceneview.ar.node.PointCloudNode.update] silently no-ops whenever ARCore isn't
 * `TRACKING` — lost tracking, a paused session, or a cold-start still resolving the first
 * feature points all look identical from the UI: zero points, forever, with no indication why.
 * `ARRawDepthPointCloudDemo` and `ARSceneSemanticsDemo` both surface a tracking-failure banner
 * plus a "stuck at zero" hint for exactly this reason (see #1617 — never leave the user staring
 * at a screen that looks broken); `ARPointCloudDemo` previously had neither, so a real tracking
 * failure read as "nothing rendered" (the #3270 report) instead of "move the device" or "not
 * enough light".
 *
 * Extracted as a pure, ARCore-free function so the gate — including the "just started" and
 * "already recovered" edges — can be pinned by a JVM unit test. See `PointCloudFeedbackTest`.
 */
internal object PointCloudFeedback {

    /** Minimum time (ms) the cloud must sit at zero points, while tracking, before the demo
     * surfaces the "still no points" hint. Mirrors `ARRawDepthPointCloudDemo`'s own threshold. */
    const val STUCK_AFTER_MS: Long = 2_000L

    /**
     * `true` when the point cloud has been stuck at zero points, **while tracking**, for at
     * least [stuckAfterMs] — i.e. long enough that "move the device" is more helpful than
     * staying silent, but not so trigger-happy that it flashes on every normal frame gap.
     *
     * Returns `false` while tracking is lost — that path already has its own (louder) banner,
     * driven by `onTrackingFailureChanged`, so this hint would only be redundant noise on top
     * of it.
     *
     * @param isTracking       Whether ARCore's camera is currently `TrackingState.TRACKING`.
     * @param pointCount       Points rendered on the most recent [io.github.sceneview.ar.node.PointCloudNode] update.
     * @param zeroPointsSinceMs Monotonic time the count first dropped to (or started at) zero;
     *                         `null` means it hasn't happened yet, or already recovered.
     * @param nowMs            Current monotonic time.
     * @param stuckAfterMs     Threshold in ms. Defaults to [STUCK_AFTER_MS].
     */
    fun zeroPointsStuck(
        isTracking: Boolean,
        pointCount: Int,
        zeroPointsSinceMs: Long?,
        nowMs: Long,
        stuckAfterMs: Long = STUCK_AFTER_MS,
    ): Boolean = isTracking &&
        pointCount == 0 &&
        zeroPointsSinceMs != null &&
        (nowMs - zeroPointsSinceMs) > stuckAfterMs

    /** Below this many accumulated points the cloud still reads as sparse (#4624). */
    const val SPARSE_BELOW: Int = 150

    /** What the status pill says about the accumulated cloud (#4624). */
    enum class Status { Empty, Sparse, Built }

    /** [Status] for [pointCount] points in the accumulated cloud. */
    fun status(pointCount: Int, sparseBelow: Int = SPARSE_BELOW): Status = when {
        pointCount <= 0 -> Status.Empty
        pointCount < sparseBelow -> Status.Sparse
        else -> Status.Built
    }
}
