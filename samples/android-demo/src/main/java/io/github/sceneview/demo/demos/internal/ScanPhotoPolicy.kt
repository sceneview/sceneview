package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Keep a recognisable room view every twenty keyframes without making every replay frame large.
 * At most 15 anchor photos at 128 KiB plus 285 small photos at 16 KiB = 6.33 MiB, about 1.6 MiB
 * above the old estimated 5 MB. A 60-photo room has only three anchors (at most 0.4 MB extra).
 * 960 pixels on the long side retains doors/windows on a phone; never upscale a smaller source.
 * These are JPEG payload limits, not a limit on the scan's geometry, log or baked plane textures.
 */
internal object ScanPhotoPolicy {
    const val ANCHOR_INTERVAL = 20
    const val ANCHOR_LONG_SIDE = 960
    const val REPLAY_LONG_SIDE = 320
    const val THUMBNAIL_LONG_SIDE = 160
    const val JPEG_QUALITY = 85
    const val MIN_JPEG_QUALITY = 65
    const val QUALITY_STEP = 10
    const val ANCHOR_BYTES = 128 * 1024
    const val REPLAY_BYTES = 16 * 1024
    const val PHOTO_BUDGET_BYTES = 6480 * 1024
    const val MAX_SPEED_MPS = 0.5f
    const val MAX_TURN_DEGREES_PER_SECOND = 45f
    const val MAX_POSE_GAP_NS = 250_000_000L

    /** A view that never settles (a slow frame rate, a tracking that keeps jumping) still gets its photo. */
    const val MAX_DEFER_NS = 1_500_000_000L

    fun isAnchor(index: Int): Boolean = index % ANCHOR_INTERVAL == 0
    fun byteLimit(index: Int): Int = if (isAnchor(index)) ANCHOR_BYTES else REPLAY_BYTES

    fun targetSize(index: Int, lens: ReplayLens, sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> =
        ScanProjection.photoSize(
            lens,
            minOf(if (isAnchor(index)) ANCHOR_LONG_SIDE else REPLAY_LONG_SIDE, maxOf(sourceWidth, sourceHeight)),
        )

    class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

    /** Retry quality first, then resolution in steps of a quarter. Never store an over-budget payload. */
    fun encode(index: Int, size: Pair<Int, Int>, jpeg: (Int, Int, Int) -> ByteArray?): Encoded? {
        var (width, height) = size
        while (true) {
            for (quality in JPEG_QUALITY downTo MIN_JPEG_QUALITY step QUALITY_STEP) {
                val bytes = jpeg(width, height, quality) ?: return null
                if (bytes.size <= byteLimit(index)) return Encoded(bytes, width, height)
            }
            if (width == 1 && height == 1) return null
            width = (width * 3 / 4).coerceAtLeast(1)
            height = (height * 3 / 4).coerceAtLeast(1)
        }
    }

    /** Old 240×320 photos still decode at sample 2; anchors keep thumbnails equally small. */
    fun thumbnailSample(width: Int, height: Int): Int {
        var sample = 2
        while (maxOf(width, height) / sample > THUMBNAIL_LONG_SIDE) sample *= 2
        return sample
    }
}

/**
 * Consecutive tracked poses, independent of accepted keyframes: a fast view waits to settle, but
 * never longer than [ScanPhotoPolicy.MAX_DEFER_NS] — a slow or jumpy scan must keep its photos.
 */
internal class ScanPhotoMotion {
    private var previous: DebugPose? = null
    private var previousNanos = 0L
    private var unsteadySince = 0L

    /** Whether the camera at [pose], [nanos] being the frame's timestamp, is calm enough for a photo. */
    fun isSteady(nanos: Long, pose: DebugPose): Boolean {
        val calm = calm(nanos, pose)
        previous = pose
        previousNanos = nanos
        if (calm) {
            unsteadySince = 0L
            return true
        }
        if (unsteadySince == 0L) unsteadySince = nanos
        if (nanos - unsteadySince < ScanPhotoPolicy.MAX_DEFER_NS) return false
        unsteadySince = nanos
        return true
    }

    private fun calm(nanos: Long, pose: DebugPose): Boolean {
        val last = previous ?: return false
        val elapsed = nanos - previousNanos
        if (elapsed <= 0L || elapsed > ScanPhotoPolicy.MAX_POSE_GAP_NS) return false
        val seconds = elapsed / 1e9
        val dx = pose.x - last.x
        val dy = pose.y - last.y
        val dz = pose.z - last.z
        val speed = sqrt((dx * dx + dy * dy + dz * dz).toDouble()) / seconds
        val dot = abs(pose.qx * last.qx + pose.qy * last.qy + pose.qz * last.qz + pose.qw * last.qw)
        val turn = Math.toDegrees(2 * acos(dot.coerceIn(0f, 1f).toDouble())) / seconds
        return speed <= ScanPhotoPolicy.MAX_SPEED_MPS && turn <= ScanPhotoPolicy.MAX_TURN_DEGREES_PER_SECOND
    }
}
