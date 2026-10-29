package io.github.sceneview.demo.demos.internal.semantic

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min

/**
 * The pre- and post-processing of a YOLOX model exported to ONNX the way its repository does
 * it (`tools/export_onnx.py`, decoding left out of the graph), and of its LiteRT conversion, which
 * reads the same input with the channels last ([LiteRtSceneDetector]): plain Kotlin, so the JVM
 * tests run the very code the device runs.
 *
 * In: the photo letterboxed into the model's square — scaled to fit, pinned to the top left,
 * the rest grey (114) —, BGR, 0–255, channels first. Out: one row per anchor point of the three
 * grids (strides 8, 16, 32), `cx, cy, w, h, objectness, 80 class scores`, the last 81 already
 * through a sigmoid.
 */
object YoloxCodec {
    /** COCO's 80 classes, in the model's order. */
    val COCO: List<String> = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog",
        "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella",
        "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite",
        "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
        "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
        "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant",
        "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
        "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors",
        "teddy bear", "hair drier", "toothbrush",
    )

    val STRIDES = intArrayOf(8, 16, 32)
    const val PAD = 114f
    const val MIN_SCORE = 0.3f
    const val NMS_IOU = 0.45f
    private const val BOX_VALUES = 5
    private const val CHANNELS = 3

    /** The input side of a model and the width of its output rows, read from its tensors. */
    data class TensorLayout(val size: Int, val columns: Int)

    /**
     * Writes [image] letterboxed into a [size] × [size] input, channels first, into [into] (3 ×
     * size² floats) and returns the scale it was drawn at: input pixels per photo pixel.
     * Bilinear, as the reference's `cv2.resize`.
     */
    fun input(image: SceneImage, size: Int, into: FloatArray): Float {
        require(into.size >= 3 * size * size) { "the input holds 3 × $size² floats" }
        val ratio = min(size.toFloat() / image.width, size.toFloat() / image.height)
        val w = min(size, (image.width * ratio).toInt())
        val h = min(size, (image.height * ratio).toInt())
        val plane = size * size
        into.fill(PAD, 0, 3 * plane)
        val px = image.argb
        for (y in 0 until h) {
            val sy = ((y + 0.5f) / ratio - 0.5f).coerceIn(0f, (image.height - 1).toFloat())
            val y0 = floor(sy).toInt()
            val y1 = min(y0 + 1, image.height - 1)
            val fy = sy - y0
            for (x in 0 until w) {
                val sx = ((x + 0.5f) / ratio - 0.5f).coerceIn(0f, (image.width - 1).toFloat())
                val x0 = floor(sx).toInt()
                val x1 = min(x0 + 1, image.width - 1)
                val fx = sx - x0
                val a = px[y0 * image.width + x0]
                val b = px[y0 * image.width + x1]
                val c = px[y1 * image.width + x0]
                val d = px[y1 * image.width + x1]
                val at = y * size + x
                // BGR: blue first.
                into[at] = mix(a, b, c, d, fx, fy, 0)
                into[plane + at] = mix(a, b, c, d, fx, fy, 8)
                into[2 * plane + at] = mix(a, b, c, d, fx, fy, 16)
            }
        }
        return ratio
    }

    @Suppress("LongParameterList")
    private fun mix(a: Int, b: Int, c: Int, d: Int, fx: Float, fy: Float, shift: Int): Float {
        val top = (a shr shift and 0xFF) * (1 - fx) + (b shr shift and 0xFF) * fx
        val bottom = (c shr shift and 0xFF) * (1 - fx) + (d shr shift and 0xFF) * fx
        return top * (1 - fy) + bottom * fy
    }

    /**
     * The detections in [output] — [rows] × [columns] floats from a [size]² input drawn at
     * [ratio] — as fractions of the [imageWidth] × [imageHeight] photo: score past [minScore],
     * the class's best, overlapping ones of a class merged ([nms]). [keep] picks the classes kept.
     */
    @Suppress("LongParameterList")
    fun decode(
        output: FloatArray,
        rows: Int,
        columns: Int,
        size: Int,
        ratio: Float,
        imageWidth: Int,
        imageHeight: Int,
        minScore: Float = MIN_SCORE,
        nmsIou: Float = NMS_IOU,
        keep: (String) -> Boolean = { true },
    ): List<Detection2D> {
        val classes = columns - BOX_VALUES
        require(classes > 0 && output.size >= rows * columns) { "$rows × $columns outputs" }
        val found = ArrayList<Detection2D>()
        var row = 0
        for (stride in STRIDES) {
            val cells = size / stride
            for (gy in 0 until cells) {
                for (gx in 0 until cells) {
                    if (row >= rows) break
                    val at = row * columns
                    row++
                    val objectness = output[at + 4]
                    if (objectness < minScore) continue
                    var best = 0
                    for (k in 1 until classes) if (output[at + BOX_VALUES + k] > output[at + BOX_VALUES + best]) best = k
                    val score = objectness * output[at + BOX_VALUES + best]
                    if (score < minScore) continue
                    val name = COCO.getOrElse(best) { "class $best" }
                    if (!keep(name)) continue
                    val cx = (output[at] + gx) * stride / ratio
                    val cy = (output[at + 1] + gy) * stride / ratio
                    val w = exp(output[at + 2]) * stride / ratio
                    val h = exp(output[at + 3]) * stride / ratio
                    found += Detection2D(
                        name,
                        score,
                        ((cx - w / 2) / imageWidth).coerceIn(0f, 1f),
                        ((cy - h / 2) / imageHeight).coerceIn(0f, 1f),
                        ((cx + w / 2) / imageWidth).coerceIn(0f, 1f),
                        ((cy + h / 2) / imageHeight).coerceIn(0f, 1f),
                    )
                }
            }
        }
        return nms(found, nmsIou)
    }

    /** [detections] best first, each dropped that overlaps a better one of its class past [iou]. */
    fun nms(detections: List<Detection2D>, iou: Float = NMS_IOU): List<Detection2D> {
        val kept = ArrayList<Detection2D>()
        for (d in detections.sortedByDescending { it.score }) {
            if (kept.none { it.name == d.name && it.iou(d) > iou }) kept += d
        }
        return kept
    }

    /**
     * [planar] — the three planes [input] writes for a [size]² input — rewritten into [into] with
     * the channels last, pixel after pixel: blue, green, red. That is how a conversion that moves
     * the export's channel axis last (NCHW to NHWC, the channels kept in their order) reads it.
     */
    fun channelsLast(planar: FloatArray, size: Int, into: FloatArray) {
        val pixels = size * size
        require(planar.size >= CHANNELS * pixels && into.size >= CHANNELS * pixels) { "3 × $size² floats" }
        for (channel in 0 until CHANNELS) {
            val plane = channel * pixels
            for (pixel in 0 until pixels) into[pixel * CHANNELS + channel] = planar[plane + pixel]
        }
    }

    /**
     * The layout of a channels-last conversion whose tensors have the shapes [input] and [output],
     * once checked against what [channelsLast] writes and [decode] reads: `[1, size, size, 3]` in,
     * `[1, rowsFor(size), 85]` out. Anything else — the channels first, the output transposed,
     * another class list — throws [IllegalArgumentException]: such a model runs without an error
     * and finds nothing but noise.
     */
    fun channelsLastLayout(input: IntArray, output: IntArray): TensorLayout {
        val size = input.getOrElse(1) { 0 }
        val square = input.contentEquals(intArrayOf(1, size, size, CHANNELS))
        require(square && size > 0 && size % STRIDES.last() == 0) {
            "input ${input.contentToString()}: not [1, side, side, 3]"
        }
        val layout = TensorLayout(size, BOX_VALUES + COCO.size)
        require(output.contentEquals(intArrayOf(1, rowsFor(size), layout.columns))) {
            "output ${output.contentToString()}: not [1, ${rowsFor(size)}, ${layout.columns}]"
        }
        return layout
    }

    /** How many rows a [size]² input gives: one per cell of the three grids. */
    fun rowsFor(size: Int): Int = STRIDES.sumOf { (size / it) * (size / it) }
}
