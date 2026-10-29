package io.github.sceneview.demo.demos.internal.semantic

import kotlin.math.max
import kotlin.math.min

/** A photo as the detector reads it: [width] × [height] pixels, `0xAARRGGBB`, row-major, top row first. */
class SceneImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size >= width * height) { "a $width × $height image needs that many pixels" }
    }
}

/**
 * One thing a detector saw in a photo: its class [name], its [score] (0–1) and its box, as
 * fractions of the photo's width and height — [left], [top] towards [right], [bottom].
 */
class Detection2D(
    val name: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** The room part the class is, `null` for one that is none ([SceneLabel.ofDetector]). */
    val label: SceneLabel? get() = SceneLabel.ofDetector(name)

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Intersection over union with the rectangle [l], [t], [r], [b], in the same fractions. */
    fun iou(l: Float, t: Float, r: Float, b: Float): Float = rectIou(left, top, right, bottom, l, t, r, b)

    fun iou(other: Detection2D): Float = iou(other.left, other.top, other.right, other.bottom)

    override fun toString() = "$name ${"%.2f".format(score)} [${"%.2f".format(left)}, ${"%.2f".format(top)}, " +
        "${"%.2f".format(right)}, ${"%.2f".format(bottom)}]"
}

/** Finds things in a photo. On the device and on the JVM alike; closed once done with. */
interface SceneDetector : AutoCloseable {
    /** What the detector is, for the logs and the corpus report: `yolox_tiny 416`. */
    val name: String

    /** What [image] shows, best first. Off the main thread: a model runs for tens of milliseconds. */
    fun detect(image: SceneImage): List<Detection2D>

    override fun close() {}
}

/** Intersection over union of two axis-aligned rectangles, each given as left, top, right, bottom. */
@Suppress("LongParameterList")
fun rectIou(al: Float, at: Float, ar: Float, ab: Float, bl: Float, bt: Float, br: Float, bb: Float): Float {
    val iw = min(ar, br) - max(al, bl)
    val ih = min(ab, bb) - max(at, bt)
    if (iw <= 0f || ih <= 0f) return 0f
    val inter = iw * ih
    val union = (ar - al) * (ab - at) + (br - bl) * (bb - bt) - inter
    return if (union <= 0f) 0f else inter / union
}
