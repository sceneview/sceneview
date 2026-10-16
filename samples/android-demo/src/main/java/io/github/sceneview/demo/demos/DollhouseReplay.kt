package io.github.sceneview.demo.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.google.android.filament.Engine
import io.github.sceneview.SceneScope
import io.github.sceneview.demo.demos.internal.ArDebugFrame
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.RoomDollhouse
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Rotation

/** Recording-relative time; zero-length recordings hold their only frame. */
internal fun dollhouseLoopTime(seconds: Float, duration: Float): Float {
    if (!seconds.isFinite() || !duration.isFinite() || duration <= 0f) return 0f
    val wrapped = seconds % duration
    return if (wrapped < 0f) wrapped + duration else wrapped
}

/** Number of cropped surfels whose original index had appeared by this recorded count. */
internal fun dollhouseRetainedPointCount(indices: IntArray, seen: Int): Int {
    var low = 0
    var high = indices.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (indices[middle] < seen) low = middle + 1 else high = middle
    }
    return low
}

/** Selects the recording's state at this point in the loop, including wrap-around. */
internal fun dollhouseReplayFrame(trace: ArDebugTrace, seconds: Float): ArDebugFrame =
    trace.frameAt(dollhouseLoopTime(seconds, trace.duration))

/**
 * One stable build owns the room, materials and textures. Only its frame changes. The clock
 * runs while placed and playing; pausing keeps the cursor, resuming discards time spent paused.
 */
@Composable
internal fun SceneScope.DollhouseReplay(
    build: DollhouseBuild,
    engine: Engine,
    materialLoader: MaterialLoader,
    playing: Boolean,
) {
    val room = build.room ?: return
    var cursor by remember(build) { mutableFloatStateOf(0f) }
    LaunchedEffect(build, playing) {
        if (!playing) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            cursor = dollhouseLoopTime(cursor + (now - previous) / 1_000_000_000f, build.media.trace.duration)
            previous = now
        }
    }
    // The recording holds a pose every few hundredths of a second: a frame is cut per tick of
    // that order, not per display frame.
    val tick = (cursor * REPLAY_TICKS_PER_SECOND).toInt()
    val frame = remember(build, tick) {
        val recorded = dollhouseReplayFrame(build.media.trace, tick / REPLAY_TICKS_PER_SECOND)
        val cropped = RoomDollhouse.crop(recorded, room.fit.floorY)
        ArDebugFrame(
            time = recorded.time,
            trail = recorded.trail,
            camera = recorded.camera,
            mapPoints = cropped.mapPoints,
            livePoints = cropped.livePoints,
            planes = cropped.planes,
            anchors = emptyList(),
            mapPointColors = cropped.mapPointColors,
            image = recorded.image,
        )
    }
    val chrome = LocalStageChrome.current
    // As Room Scan stands it: the side the recording started from faces the user.
    val orientation = remember(room) { RoomDollhouse.orientation(room) }
    Node(rotation = Rotation(y = orientation.yawDegrees)) {
        DollhouseModel(
            build = build, room = room, engine = engine, materialLoader = materialLoader,
            palette = chrome.debug, base = chrome.card, scale = room.fit.scale,
            styleScale = room.fit.scale, pickable = true, showPath = true, currentFrame = frame,
        )
    }
}

/** How often the replayed frame is cut again: the recording's own order of pose rate. */
private const val REPLAY_TICKS_PER_SECOND = 30f
