package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Keep a few recognisable room views without making every replay frame large. At most
 * [MAX_SHARP] sharp photos at 128 KiB plus the others at 24 KiB (the old pipeline averaged about
 * 16.7 KB, so ordinary photos come out as before): 15 x 128 + 285 x 24 = 8,760 KiB, 8.55 MiB over
 * [KeyframeGate.MAX_PHOTOS] photos. The sharp ones are up to 960 pixels on the long side, never
 * upscaled: the real size is the ARCore camera image's (540x960 or 480x640 on common phones).
 * These are JPEG payload limits, not a limit on the scan's geometry, log or baked plane textures.
 */
internal object ScanPhotoPolicy {
    /** A sharp photo comes once at least this many photos after the previous one, when calm. */
    const val SHARP_INTERVAL = 20
    const val MAX_SHARP = 15

    /** A scan that never calms down still gets one sharp photo, after this many ordinary ones. */
    const val SHARP_GUARANTEE_AFTER = 40
    const val SHARP_LONG_SIDE = 960
    const val REPLAY_LONG_SIDE = 320
    const val THUMBNAIL_LONG_SIDE = 160
    const val JPEG_QUALITY = 85
    const val MIN_JPEG_QUALITY = 65
    const val QUALITY_STEP = 10
    const val SHARP_BYTES = 128 * 1024
    const val REPLAY_BYTES = 24 * 1024
    const val PHOTO_BUDGET_BYTES =
        MAX_SHARP * SHARP_BYTES + (KeyframeGate.MAX_PHOTOS - MAX_SHARP) * REPLAY_BYTES
    const val MAX_SPEED_MPS = 0.5f
    const val MAX_TURN_DEGREES_PER_SECOND = 45f
    const val MAX_POSE_GAP_NS = 250_000_000L

    fun byteLimit(sharp: Boolean): Int = if (sharp) SHARP_BYTES else REPLAY_BYTES

    fun targetSize(sharp: Boolean, lens: ReplayLens, sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> =
        ScanProjection.photoSize(
            lens,
            minOf(if (sharp) SHARP_LONG_SIDE else REPLAY_LONG_SIDE, maxOf(sourceWidth, sourceHeight)),
        )

    class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

    /** Retry quality first, then resolution in steps of a quarter. Never store an over-budget payload. */
    fun encode(sharp: Boolean, size: Pair<Int, Int>, jpeg: (Int, Int, Int) -> ByteArray?): Encoded? {
        var (width, height) = size
        while (true) {
            for (quality in JPEG_QUALITY downTo MIN_JPEG_QUALITY step QUALITY_STEP) {
                val bytes = jpeg(width, height, quality) ?: return null
                if (bytes.size <= byteLimit(sharp)) return Encoded(bytes, width, height)
            }
            if (width == 1 && height == 1) return null
            width = (width * 3 / 4).coerceAtLeast(1)
            height = (height * 3 / 4).coerceAtLeast(1)
        }
    }

    /** The largest power-of-two `inSampleSize` that keeps the long side at [longSide] or more. */
    fun sampleFor(width: Int, height: Int, longSide: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= longSide) sample *= 2
        return sample
    }

    /** Decode sample of a replay thumbnail: 240x320 photos still decode at 2, as before. */
    fun thumbnailSample(width: Int, height: Int): Int = sampleFor(width, height, THUMBNAIL_LONG_SIDE)

    /** Decode sample of a session card's picture: a 240x320 photo decodes whole, a sharp one at half or less. */
    fun previewSample(width: Int, height: Int): Int = sampleFor(width, height, REPLAY_LONG_SIDE)

    /**
     * A thumbnail's size: [THUMBNAIL_LONG_SIDE] on the long side, whichever photo it comes from, so a
     * sharp photo's thumbnail is as large as its neighbours' (240x320 gives 120x160, as before).
     */
    fun thumbnailSize(width: Int, height: Int): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= THUMBNAIL_LONG_SIDE) return width to height
        val scale = THUMBNAIL_LONG_SIDE.toFloat() / long
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }
}

/**
 * Which photos are sharp: the first calm one of the scan, then the first calm one once
 * [ScanPhotoPolicy.SHARP_INTERVAL] photos have passed since the last, [ScanPhotoPolicy.MAX_SHARP] at
 * most. A sharp photo waits for a calm camera, however long; the ordinary photos never wait. The one
 * guarantee: a scan whose camera is never calm still gets one sharp photo, after
 * [ScanPhotoPolicy.SHARP_GUARANTEE_AFTER] photos.
 */
internal class ScanSharpPicker {
    var sharpCount = 0
        private set
    private var sinceSharp = 0
    private var total = 0

    /** Whether the next photo, taken while the camera is [calm] or not, is a sharp one. */
    fun nextIsSharp(calm: Boolean): Boolean {
        if (sharpCount >= ScanPhotoPolicy.MAX_SHARP) return false
        val due = sharpCount == 0 || sinceSharp >= ScanPhotoPolicy.SHARP_INTERVAL
        if (due && calm) return true
        return sharpCount == 0 && total >= ScanPhotoPolicy.SHARP_GUARANTEE_AFTER
    }

    /** Records the photo just taken. */
    fun accept(sharp: Boolean) {
        total++
        if (sharp) {
            sharpCount++
            sinceSharp = 0
        } else {
            sinceSharp++
        }
    }
}

/** Consecutive tracked poses, independent of accepted keyframes: whether the camera is calm. */
internal class ScanPhotoMotion {
    private var previous: DebugPose? = null
    private var previousNanos = 0L

    /**
     * Whether the camera at [pose], [nanos] being the frame's timestamp, is calm enough for a sharp
     * photo. Call it on every tracked frame; the first one, and the first after a tracking gap, are not.
     */
    fun isCalm(nanos: Long, pose: DebugPose): Boolean {
        val last = previous
        val elapsed = nanos - previousNanos
        previous = pose
        previousNanos = nanos
        if (last == null || elapsed <= 0L || elapsed > ScanPhotoPolicy.MAX_POSE_GAP_NS) return false
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
