package io.github.sceneview.demo.demos.internal

import io.github.sceneview.capture.VideoSettings
import kotlin.math.ceil
import kotlin.math.sin

/**
 * The scan result's film (`camera.md`, rule 10): asked for with Video only, the result's own camera
 * ([ScanResultCamera.startFilm]) sweeps [SWING_DEGREES] either side of the start and back in
 * [SECONDS], then stops, while `SceneRecorder` encodes it. The same centre, height of view and
 * distance throughout: the clip shows the scan as the result does, turned a little both ways.
 *
 * Pure: [azimuthOffsetAt] is the heading off the start at any time of the sweep, what the tests pin.
 */
internal object ResultFilm {
    /** The sweep, start to start. */
    const val SECONDS = 8f

    /** How far it turns either side of the start, degrees. */
    const val SWING_DEGREES = 40f

    /** The clip's frame rate: what the tests step the film at. */
    const val FPS = 30

    /** The clip's frames at [FPS], the last one back on the start. */
    val frameCount: Int get() = ceil(SECONDS * FPS).toInt()

    /**
     * The heading off the start [seconds] into the sweep, degrees: out to [SWING_DEGREES] one way,
     * through the start, out the other way and back, on an ease that leaves the start and lands on
     * it at rest. 0 before the sweep and after it.
     */
    fun azimuthOffsetAt(seconds: Float): Float {
        val t = (seconds / SECONDS).coerceIn(0f, 1f)
        return SWING_DEGREES * sin(2.0 * Math.PI * ScanResultCamera.easeInOut(t)).toFloat()
    }

    /**
     * What the clip is encoded at: 720p on its short edge (the view's own shape, so a phone held
     * upright films upright), [FPS], and a hard stop at twice the sweep: only a safety net, since
     * the camera moves in frame time, so a slow device stretches the film.
     */
    val video: VideoSettings
        get() = VideoSettings(
            maxLongEdge = VIDEO_LONG_EDGE,
            maxShortEdge = VIDEO_SHORT_EDGE,
            frameRate = FPS,
            bitRate = VIDEO_BIT_RATE,
            maxDurationMillis = ceil(SECONDS * MILLIS * STOP_FACTOR).toLong(),
        )

    /**
     * Whether a running take is dropped: its recording stopped (the app left the foreground, the
     * encoder failed or hit its hard stop), or the camera stopped filming before it [landed] (a
     * touch cut the sweep short).
     */
    fun aborts(landed: Boolean, cameraFilming: Boolean, recording: Boolean): Boolean =
        !recording || (!landed && !cameraFilming)

    private const val VIDEO_LONG_EDGE = 1280
    private const val VIDEO_SHORT_EDGE = 720
    private const val VIDEO_BIT_RATE = 8_000_000
    private const val MILLIS = 1000f
    private const val STOP_FACTOR = 2f
}
