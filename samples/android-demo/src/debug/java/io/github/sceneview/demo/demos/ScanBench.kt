package io.github.sceneview.demo.demos

import android.os.SystemClock
import android.util.Log
import io.github.sceneview.demo.demos.internal.ArDebugFrame
import io.github.sceneview.demo.demos.internal.RoomDollhouse
import io.github.sceneview.demo.demos.internal.ScanGuide
import java.util.WeakHashMap
import kotlin.math.hypot

/**
 * Read-only debug observations of the live scan, once per full second and on the first
 * surface. This runs before GPU presentation: `surface_ready` is a geometry-ready proxy,
 * not a pixel visibility assertion. No capture, coverage or rendering state is changed.
 */
internal fun scanBenchObserve(
    media: RerunReplayMedia,
    frame: ArDebugFrame,
    coverage: ScanCoverageState,
    dense: ReplayDenseLayer?,
    walls: ScanWallsState?,
) {
    val now = SystemClock.elapsedRealtime()
    val state = benchStates.getOrPut(media) { BenchState(now) }
    val ready = (dense?.mesh?.indices?.size ?: 0) > 0 ||
        walls?.snapshot?.walls?.isNotEmpty() == true || frame.mapPoints.isNotEmpty() ||
        frame.livePoints.isNotEmpty()
    val firstSurface = ready && !state.surfaceSeen
    if (now - state.windowStart < 1_000 && !firstSurface) {
        state.calls++
        return
    }
    state.surfaceSeen = state.surfaceSeen || ready

    // The drawn dense surfels are the counted elements. A room needs closed detected walls;
    // neither an expanding cloud hull nor a partial ARCore floor plane is room ground truth.
    val closed = walls != null && ScanGuide.closed(walls.wallCount, walls.openEdges)
    val points = dense?.cloud?.positions
    val outline = if (closed) RoomDollhouse.convexHull(
        walls!!.snapshot.walls.flatMap { listOf(it.ax to it.az, it.bx to it.bz) },
    ) else emptyList()
    val outside = if (outline.size >= 3 && points != null) {
        var count = 0
        var i = 0
        while (i + 2 < points.size) {
            if (outsideWallHull(outline, points[i], points[i + 2])) count++
            i += 3
        }
        count.toString()
    } else "null"
    fun log(event: String, fps: Int? = null, epochStart: Long? = null) {
        Log.i(
            "ScanBench",
            "{\"schema\":2,\"event\":\"$event\",\"fps\":$fps," +
                "\"window_start_epoch_ms\":$epochStart,\"tracking_state\":null," +
                "\"camera_pose_available\":${frame.camera != null}," +
                "\"points\":${dense?.cloud?.positions?.size?.div(3) ?: frame.mapPointCount}," +
                "\"trace_s\":${frame.time},\"surface_ready\":$ready," +
                "\"covered_m2\":${coverage.coveredSquareMetres},\"outside_elements\":$outside," +
                "\"outside_basis\":\"drawn_surfels_closed_wall_convex_hull_xz_10cm\"}",
        )
    }
    if (firstSurface) log("surface_ready")
    // Count this hook's calls, including repeated trace frames. A trace's timestamps do not
    // measure rendering. Close full one-second buckets before counting the current call;
    // gaps produce zero buckets when rendering resumes, rather than hiding a stall.
    while (now - state.windowStart >= 1_000) {
        log("cadence", state.calls, System.currentTimeMillis() - (now - state.windowStart))
        state.calls = 0
        state.windowStart += 1_000
    }
    state.calls++
}

/** Hull winding may vary; 10 cm keeps points on a noisy wall inside either winding. */
private fun outsideWallHull(hull: List<Pair<Float, Float>>, x: Float, z: Float): Boolean {
    var positive = false
    var negative = false
    for (i in hull.indices) {
        val (ax, az) = hull[i]
        val (bx, bz) = hull[(i + 1) % hull.size]
        val cross = (bx - ax) * (z - az) - (bz - az) * (x - ax)
        val tolerance = 0.10f * hypot(bx - ax, bz - az)
        if (cross > tolerance) positive = true
        if (cross < -tolerance) negative = true
    }
    return positive && negative
}

// ArDebugFrame retains a camera pose; it cannot report ARCore's current TrackingState.
private class BenchState(
    var windowStart: Long,
    var calls: Int = 0,
    var surfaceSeen: Boolean = false,
)
private val benchStates = WeakHashMap<RerunReplayMedia, BenchState>()
