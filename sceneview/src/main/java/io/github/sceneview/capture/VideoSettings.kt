package io.github.sceneview.capture

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * How a [SceneRecorder] encodes a video.
 *
 * The video keeps the scene view's own aspect ratio and is fitted inside a
 * [maxLongEdge] × [maxShortEdge] box turned the same way as the view: a portrait phone records a
 * portrait video, a landscape tablet a landscape one, with no black bars. Because the recording is
 * a second render of the scene (see [io.github.sceneview.utils.SurfaceMirrorer]), not a copy of the
 * screen, a view smaller than the box is still recorded at the full box size. When the device's
 * H.264 encoder refuses that size the recorder steps down to 720p, then 540p, then 360p.
 *
 * ```kotlin
 * recorder.startRecording(
 *     settings = VideoSettings(recordAudio = true, maxDurationMillis = 30_000)
 * )
 * ```
 *
 * @property maxLongEdge       Longest edge of the video, in pixels. Default 1920 (1080p).
 * @property maxShortEdge      Shortest edge of the video, in pixels. Default 1080 (1080p).
 * @property frameRate         Encoded frames per second. Default 30.
 * @property bitRate           H.264 bit rate, in bits per second. Default 12 Mbit/s.
 * @property recordAudio       Also record the microphone (AAC, mono). Needs the app to hold
 *                             `android.permission.RECORD_AUDIO` at the time recording starts;
 *                             without it the video is recorded silent and
 *                             [SceneRecording.hasAudio] is `false`. Default `false`.
 * @property maxDurationMillis Stop the recording by itself after this long, in milliseconds.
 *                             `0` = no limit, the default.
 */
data class VideoSettings(
    val maxLongEdge: Int = 1920,
    val maxShortEdge: Int = 1080,
    val frameRate: Int = 30,
    val bitRate: Int = 12_000_000,
    val recordAudio: Boolean = false,
    val maxDurationMillis: Long = 0L,
) {
    init {
        require(maxShortEdge > 0 && maxLongEdge >= maxShortEdge) {
            "Need 0 < maxShortEdge <= maxLongEdge, got $maxLongEdge x $maxShortEdge"
        }
        require(frameRate > 0) { "frameRate must be positive, got $frameRate" }
        require(bitRate > 0) { "bitRate must be positive, got $bitRate" }
        require(maxDurationMillis >= 0) { "maxDurationMillis must be >= 0, got $maxDurationMillis" }
    }
}

/** A video frame size, in pixels. */
internal data class VideoSize(val width: Int, val height: Int)

/** Smaller boxes tried, in order, when the encoder refuses the requested one. */
private val FALLBACK_BOXES = listOf(1280 to 720, 960 to 540, 640 to 360)

/** H.264 4:2:0 needs even edges; some encoders only take multiples of 16. */
private val ALIGNMENTS = intArrayOf(2, 16)

/**
 * The size to encode a [sourceWidth] × [sourceHeight] scene view at: its aspect ratio fitted in a
 * [maxLongEdge] × [maxShortEdge] box oriented like the view, edges aligned down to 2 (then 16),
 * the first size [isSupported] accepts, stepping down through 720p, 540p and 360p.
 *
 * Integer arithmetic throughout: `2400 * 0.8` in floating point is not always 1920.
 *
 * @return `null` before the view is laid out, or when the encoder accepts none of the sizes.
 */
internal fun chooseVideoSize(
    sourceWidth: Int,
    sourceHeight: Int,
    maxLongEdge: Int,
    maxShortEdge: Int,
    isSupported: (width: Int, height: Int) -> Boolean = { _, _ -> true },
): VideoSize? {
    if (sourceWidth <= 0 || sourceHeight <= 0) return null
    val boxes = listOf(maxLongEdge to maxShortEdge) + FALLBACK_BOXES.filter { it.first < maxLongEdge }
    for ((longEdge, shortEdge) in boxes) {
        for (alignment in ALIGNMENTS) {
            val size = fit(sourceWidth, sourceHeight, longEdge, shortEdge, alignment) ?: continue
            if (isSupported(size.width, size.height)) return size
        }
    }
    return null
}

private fun fit(
    sourceWidth: Int,
    sourceHeight: Int,
    longEdge: Int,
    shortEdge: Int,
    alignment: Int,
): VideoSize? {
    val portrait = sourceHeight > sourceWidth
    val boxWidth = (if (portrait) shortEdge else longEdge).toLong()
    val boxHeight = (if (portrait) longEdge else shortEdge).toLong()
    val (width, height) = if (sourceWidth * boxHeight >= sourceHeight * boxWidth) {
        boxWidth to sourceHeight * boxWidth / sourceWidth
    } else {
        sourceWidth * boxHeight / sourceHeight to boxHeight
    }
    val alignedWidth = (width / alignment * alignment).toInt()
    val alignedHeight = (height / alignment * alignment).toInt()
    return if (alignedWidth > 0 && alignedHeight > 0) VideoSize(alignedWidth, alignedHeight) else null
}

/**
 * Whether one of the device's H.264 encoders takes a [width] × [height] input. `true` when the
 * device lists no H.264 encoder capabilities at all, so the decision is left to `MediaRecorder`.
 */
internal fun isAvcEncoderSizeSupported(width: Int, height: Int): Boolean {
    val capabilities = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter(MediaCodecInfo::isEncoder)
            .mapNotNull { info ->
                runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }
                    .getOrNull()?.videoCapabilities
            }
    }.getOrDefault(emptyList())
    return capabilities.isEmpty() || capabilities.any { it.isSizeSupported(width, height) }
}
