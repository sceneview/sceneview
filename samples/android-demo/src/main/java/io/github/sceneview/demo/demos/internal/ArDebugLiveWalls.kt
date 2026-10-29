package io.github.sceneview.demo.demos.internal

/*
 * 3D Scan's trace handed to the live walls (#4540): the walls themselves know nothing of the
 * trace ([LivePlane]), so Measure and a scan module can feed them ARCore's planes directly.
 */

/**
 * The recorder writes nothing while ARCore has lost tracking, so a trace with no camera pose for
 * longer than this, in seconds, had lost it (or the app was away). The recorder writes a pose on
 * every frame ARCore tracks, 30 a second: a frame or two late is still tracking.
 */
const val TRACE_LOST_AFTER_S = 0.25f

/**
 * Whether ARCore lost tracking between the trace times [from] and [to], seconds: two camera poses
 * the recorder journaled in between are more than [TRACE_LOST_AFTER_S] apart. How often the live
 * view observes a frame says nothing of it — a busy phone observing three frames a second still
 * tracks. `false` with no journal (a replay, a scan finished) or when [to] is not after [from].
 */
fun ArDebugTrace.lostBetween(from: Float, to: Float): Boolean {
    val events = journal ?: return false
    if (!(from < to)) return false
    var after = Float.NaN
    for (i in events.indices.reversed()) {
        val pose = events[i] as? ArDebugEvent.CameraPose ?: continue
        val t = secondsOf(pose.nanos)
        if (t > to) continue
        if (!after.isNaN() && after - t > TRACE_LOST_AFTER_S) return true
        if (t <= from) return false
        after = t
    }
    return false
}

/** [this] plane as the live walls read it. */
fun DebugPlane.toLivePlane(): LivePlane = LivePlane(
    id = id,
    kind = when (kind) {
        DebugPlaneKind.Floor -> LivePlane.Kind.Floor
        DebugPlaneKind.Ceiling -> LivePlane.Kind.Ceiling
        DebugPlaneKind.Wall -> LivePlane.Kind.Wall
        DebugPlaneKind.Unknown -> LivePlane.Kind.Other
    },
    polygon = polygon,
)

/** [frame]'s planes as the live walls read them. */
fun livePlanesOf(frame: ArDebugFrame): List<LivePlane> = frame.planes.map { it.toLivePlane() }

/** The walls [frame] shows: its planes, over the floor the trace found, at its time; [tracking] as [LiveWallSource.update]'s. */
fun LiveWallSource.update(frame: ArDebugFrame, tracking: Boolean = true): LiveWallSnapshot =
    update(livePlanesOf(frame), ArDebugGeometry.floorHeight(frame), frame.time, tracking)
