package io.github.sceneview.demo.demos.internal.semantic

import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.RerunImageCodec
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.ScanBox
import io.github.sceneview.demo.demos.internal.ScanObjects
import io.github.sceneview.demo.demos.internal.SyntheticScene
import io.github.sceneview.demo.demos.internal.SyntheticShape
import io.github.sceneview.demo.demos.internal.Vec3
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the recognition tests share, in plain JVM: photos through `ImageIO` instead of
 * `BitmapFactory`, a detector that knows the synthetic room, and the annotated pictures the corpus
 * run writes. Pictures are [SceneImage]s throughout; [Awt] does the decoding, encoding and drawing.
 */
internal object SceneTestKit {
    /** [bytes] (a JPEG or a PNG) as a [SceneImage], its longer side at most [maxSide]; `null` if unreadable. */
    fun sceneImage(bytes: ByteArray, maxSide: Int = SCENE_IMAGE_MAX_SIDE): SceneImage? =
        Awt.decode(bytes)?.let { scaled(it, maxSide) }

    private fun scaled(image: SceneImage, maxSide: Int): SceneImage {
        val side = max(image.width, image.height)
        if (side <= maxSide) return image
        val w = (image.width.toLong() * maxSide / side).toInt().coerceAtLeast(1)
        val h = (image.height.toLong() * maxSide / side).toInt().coerceAtLeast(1)
        return Awt.Canvas(w, h).apply { draw(image, 0, 0, w, h) }.picture()
    }

    /** A synthetic depth frame's colours as the JPEG a phone would have stored next to it. */
    fun jpeg(frame: DepthFrame): ByteArray = Awt.encode(SceneImage(frame.width, frame.height, frame.colors), "jpg")

    fun png(image: SceneImage): ByteArray = Awt.encode(image, "png")

    /** The lens of a synthetic frame. */
    fun lensOf(frame: DepthFrame): ReplayLens =
        ReplayLens(frame.width / 2f / frame.fx, frame.height / 2f / frame.fy)

    /** A detector-free [RerunImageCodec] for reading `.rrd` files on the JVM. */
    object ImageIoCodec : RerunImageCodec {
        override fun photo(encoded: ByteArray): RerunImageCodec.Photo? {
            val type = when {
                RerunImageCodec.isJpeg(encoded) -> RerunImageCodec.JPEG
                RerunImageCodec.isPng(encoded) -> RerunImageCodec.PNG
                else -> return null
            }
            val image = Awt.decode(encoded) ?: return null
            return RerunImageCodec.Photo(encoded, type, image.width, image.height)
        }

        override fun rgbPixels(encoded: ByteArray, maxDimension: Int): RerunImageCodec.Pixels? {
            val image = sceneImage(encoded, maxDimension) ?: return null
            val data = ByteArray(image.width * image.height * RerunImageCodec.RGB)
            for (i in 0 until image.width * image.height) {
                val argb = image.argb[i]
                data[i * 3] = (argb shr 16).toByte()
                data[i * 3 + 1] = (argb shr 8).toByte()
                data[i * 3 + 2] = argb.toByte()
            }
            return RerunImageCodec.Pixels(image.width, image.height, data)
        }

        override fun encodePng(pixels: RerunImageCodec.Pixels): ByteArray? {
            val argb = IntArray(pixels.width * pixels.height) { i ->
                val at = i * pixels.channels
                OPAQUE or (pixels.data[at].toInt() and 0xFF shl 16) or
                    (pixels.data[at + 1].toInt() and 0xFF shl 8) or (pixels.data[at + 2].toInt() and 0xFF)
            }
            return png(SceneImage(pixels.width, pixels.height, argb))
        }

        override fun transcode(encoded: ByteArray): RerunImageCodec.Transcoded? = when {
            RerunImageCodec.isJpeg(encoded) -> RerunImageCodec.Transcoded(encoded, RerunImageCodec.JPEG, false)
            RerunImageCodec.isPng(encoded) -> RerunImageCodec.Transcoded(encoded, RerunImageCodec.PNG, false)
            else -> null
        }
    }

    /**
     * [image] with [detections] outlined (yellow, the detector's own name and score) and the
     * recognised [items] as seen by [camera] (green, their label): what a keyframe of a corpus
     * run is checked with.
     */
    fun annotate(
        image: SceneImage,
        detections: List<Detection2D>,
        items: List<RecognizedItem> = emptyList(),
        camera: SceneCamera? = null,
    ): SceneImage {
        val g = Awt.Canvas(image.width, image.height)
        g.draw(image, 0, 0, image.width, image.height)
        val stroke = max(1f, max(image.width, image.height) / STROKE_DIVISOR)
        g.style(stroke, max(FONT_MIN, (stroke * FONT_PER_STROKE).roundToInt()))
        for (d in detections) {
            val x = (d.left * image.width).roundToInt()
            val y = (d.top * image.height).roundToInt()
            g.rect(x, y, (d.width * image.width).roundToInt(), (d.height * image.height).roundToInt(), YELLOW)
            g.label("${d.name} ${(d.score * PERCENT).roundToInt()}%", x, y, YELLOW)
        }
        if (camera != null) {
            for (item in items) {
                val corners = item.box.corners().map { camera.project(it) }
                if (corners.any { it == null }) continue
                for ((a, b) in ScanBox.EDGES) {
                    val p = corners[a]!!
                    val q = corners[b]!!
                    g.line(
                        (p[0] * image.width).roundToInt(), (p[1] * image.height).roundToInt(),
                        (q[0] * image.width).roundToInt(), (q[1] * image.height).roundToInt(), GREEN,
                    )
                }
                val top = corners.subList(4, 8).minBy { it!![1] }!!
                g.label(
                    "${item.label.wireName} ${(item.confidence * PERCENT).roundToInt()}%",
                    (top[0] * image.width).roundToInt(), (top[1] * image.height).roundToInt(), GREEN,
                )
            }
        }
        return g.picture()
    }

    /** [images] side by side, [columns] a row, each fitted into a [cell]-pixel square. */
    fun contactSheet(images: List<SceneImage>, columns: Int = 4, cell: Int = CONTACT_CELL): SceneImage {
        // Each picture fitted into a square cell, portrait or landscape, centred.
        val rows = (images.size + columns - 1) / columns
        val sheet = Awt.Canvas(columns * cell, max(1, rows * cell))
        images.forEachIndexed { i, image ->
            val scale = minOf(cell.toFloat() / image.width, cell.toFloat() / image.height)
            val w = (image.width * scale).toInt()
            val h = (image.height * scale).toInt()
            sheet.draw(image, (i % columns) * cell + (cell - w) / 2, (i / columns) * cell + (cell - h) / 2, w, h)
        }
        return sheet.picture()
    }

    private const val STROKE_DIVISOR = 320f
    private const val FONT_PER_STROKE = 7f
    private const val FONT_MIN = 9
    private const val PERCENT = 100f
    private const val CONTACT_CELL = 480
    private const val OPAQUE = 0xFF shl 24

    // Annotation colours on a test's output picture, not UI.
    private const val YELLOW = 0xFFFFD400.toInt()
    private const val GREEN = 0xFF00E060.toInt()
}

/**
 * `java.awt` and `javax.imageio`, reached by reflection: an Android module's unit tests compile
 * against `android.jar`, which has neither, though the JVM they run on does.
 */
internal object Awt {
    private val bufferedImage = Class.forName("java.awt.image.BufferedImage")
    private val imageIo = Class.forName("javax.imageio.ImageIO")
    private val graphics = Class.forName("java.awt.Graphics2D")
    private val fontMetrics = Class.forName("java.awt.FontMetrics")
    private val color = Class.forName("java.awt.Color")
    private val hints = Class.forName("java.awt.RenderingHints")
    private const val TYPE_INT_RGB = 1
    private const val LABEL_BACKDROP = 0xAA000000.toInt()

    /** [bytes] decoded, `null` when ImageIO cannot read them. */
    fun decode(bytes: ByteArray): SceneImage? {
        val image = runCatching { static(imageIo, "read", ByteArrayInputStream(bytes) as InputStream) }.getOrNull()
            ?: return null
        return pictureOf(image)
    }

    /** [image] as a [format] (`png`, `jpg`) file's bytes. */
    fun encode(image: SceneImage, format: String): ByteArray {
        val out = ByteArrayOutputStream()
        static(imageIo, "write", bufferedOf(image), format, out as OutputStream)
        return out.toByteArray()
    }

    private fun bufferedOf(picture: SceneImage): Any =
        bufferedImage.getConstructor(Int::class.java, Int::class.java, Int::class.java)
            .newInstance(picture.width, picture.height, TYPE_INT_RGB)
            .also { call(bufferedImage, it, "setRGB", 0, 0, picture.width, picture.height, picture.argb, 0, picture.width) }

    private fun pictureOf(image: Any): SceneImage {
        val w = call(bufferedImage, image, "getWidth") as Int
        val h = call(bufferedImage, image, "getHeight") as Int
        return SceneImage(w, h, call(bufferedImage, image, "getRGB", 0, 0, w, h, null, 0, w) as IntArray)
    }

    /** A picture being drawn on. */
    class Canvas(width: Int, height: Int) {
        private val image = bufferedImage.getConstructor(Int::class.java, Int::class.java, Int::class.java)
            .newInstance(width, height, TYPE_INT_RGB)
        private val g = call(bufferedImage, image, "createGraphics")!!

        init {
            hint("KEY_ANTIALIASING", "VALUE_ANTIALIAS_ON")
            hint("KEY_INTERPOLATION", "VALUE_INTERPOLATION_BILINEAR")
        }

        private fun hint(key: String, value: String) {
            call(graphics, g, "setRenderingHint", hints.getField(key).get(null), hints.getField(value).get(null))
        }

        private fun paint(argb: Int) {
            call(graphics, g, "setColor", color.getConstructor(Int::class.java, Boolean::class.java).newInstance(argb, true))
        }

        /** Lines [stroke] pixels wide, text of [fontSize] pixels. */
        fun style(stroke: Float, fontSize: Int) {
            val basic = Class.forName("java.awt.BasicStroke").getConstructor(Float::class.java).newInstance(stroke)
            call(graphics, g, "setStroke", basic)
            val font = Class.forName("java.awt.Font").getConstructor(String::class.java, Int::class.java, Int::class.java)
                .newInstance("SansSerif", 1, fontSize)
            call(graphics, g, "setFont", font)
        }

        fun draw(picture: SceneImage, x: Int, y: Int, w: Int, h: Int) {
            call(graphics, g, "drawImage", bufferedOf(picture), x, y, w, h, null)
        }

        fun rect(x: Int, y: Int, w: Int, h: Int, argb: Int) {
            paint(argb)
            call(graphics, g, "drawRect", x, y, w, h)
        }

        fun line(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) {
            paint(argb)
            call(graphics, g, "drawLine", x0, y0, x1, y1)
        }

        /** [text] on a dark backdrop, its bottom left at ([x], [y]). */
        fun label(text: String, x: Int, y: Int, argb: Int) {
            val metrics = call(graphics, g, "getFontMetrics")!!
            val w = call(fontMetrics, metrics, "stringWidth", text) as Int + 4
            val h = call(fontMetrics, metrics, "getHeight") as Int
            val ascent = call(fontMetrics, metrics, "getAscent") as Int
            val top = (y - h).coerceAtLeast(0)
            paint(LABEL_BACKDROP)
            call(graphics, g, "fillRect", x, top, w, h)
            paint(argb)
            call(graphics, g, "drawString", text, x + 2, top + ascent)
        }

        fun picture(): SceneImage = pictureOf(image)
    }

    private fun static(type: Class<*>, name: String, vararg args: Any?): Any? = call(type, null, name, *args)

    /** [type]'s public method [name] that takes [args], invoked on [target] (`null`: a static one). */
    private fun call(type: Class<*>, target: Any?, name: String, vararg args: Any?): Any? {
        val method = type.methods.first { m ->
            m.name == name && m.parameterCount == args.size &&
                m.parameterTypes.withIndex().all { (i, p) -> accepts(p, args[i]) }
        }
        return method.invoke(target, *args)
    }

    private fun accepts(type: Class<*>, arg: Any?): Boolean = when {
        arg == null -> !type.isPrimitive
        type == Int::class.javaPrimitiveType -> arg is Int
        type == Float::class.javaPrimitiveType -> arg is Float
        type == Boolean::class.javaPrimitiveType -> arg is Boolean
        type.isPrimitive -> false
        else -> type.isInstance(arg)
    }
}

/**
 * The detector a synthetic room deserves: it sees exactly the [truth] boxes a photo shows, their
 * projected outline as the detection, named as a COCO model would name them. A box is seen when
 * at least [MIN_VISIBLE] of the photo's pixels inside its outline land on it — the rest is hidden
 * — and its outline covers at least [MIN_AREA] of the photo.
 *
 * It knows a photo by the frame it was rendered from: the same [SceneImage], or failing that
 * (a photo that went through a JPEG) the closest one.
 */
internal class TruthDetector(
    private val frames: List<DepthFrame>,
    private val truth: List<Pair<String, ScanBox>>,
) : SceneDetector {
    override val name = "truth"

    private val images = frames.map { frame -> SceneImage(frame.width, frame.height, frame.colors) }
    private val byIdentity = IdentityHashMap<SceneImage, Int>().apply { images.forEachIndexed { i, image -> put(image, i) } }

    /** The photo of frame [index], as the recogniser should be given it. */
    fun image(index: Int): SceneImage = images[index]

    override fun detect(image: SceneImage): List<Detection2D> {
        val index = byIdentity[image] ?: closest(image) ?: return emptyList()
        val frame = frames[index]
        val camera = SceneCamera(frame.pose, SceneTestKit.lensOf(frame))
        return truth.mapNotNull { (name, box) ->
            val rect = camera.rectOf(box) ?: return@mapNotNull null
            val (l, t, r, b) = rect.toList()
            if ((r - l) * (b - t) < MIN_AREA || visible(frame, box, l, t, r, b) < MIN_VISIBLE) return@mapNotNull null
            Detection2D(name, SCORE, l, t, r, b)
        }
    }

    private fun closest(image: SceneImage): Int? = images.indices
        .filter { images[it].width == image.width && images[it].height == image.height }
        .minByOrNull { i ->
            var diff = 0L
            val a = images[i].argb
            for (p in image.argb.indices step SAMPLE_STEP) {
                diff += abs((a[p] shr 8 and 0xFF) - (image.argb[p] shr 8 and 0xFF))
            }
            diff
        }

    /** The fraction of the frame's pixels inside ([l], [t], [r], [b]) whose depth lands in [box]. */
    @Suppress("LongParameterList")
    private fun visible(frame: DepthFrame, box: ScanBox, l: Float, t: Float, r: Float, b: Float): Float {
        var inside = 0
        var all = 0
        val grown = ScanBox(box.centerX, box.centerZ, box.baseY - SLACK_M, box.yaw, box.length + 2 * SLACK_M,
            box.depth + 2 * SLACK_M, box.height + 2 * SLACK_M)
        for (py in (t * frame.height).toInt() until (b * frame.height).toInt().coerceAtMost(frame.height)) {
            for (px in (l * frame.width).toInt() until (r * frame.width).toInt().coerceAtMost(frame.width)) {
                all++
                val mm = frame.depthMm[py * frame.width + px].toInt() and 0xFFFF
                if (mm == 0) continue
                val z = mm / MM_PER_M
                val p = frame.pose.transform((px - frame.cx) / frame.fx * z, -(py - frame.cy) / frame.fy * z, -z)
                if (p.y in grown.baseY..grown.baseY + grown.height && SceneFusion.inside(grown, p.x, p.z)) inside++
            }
        }
        return if (all == 0) 0f else inside.toFloat() / all
    }

    override fun close() = Unit

    companion object {
        const val MIN_VISIBLE = 0.3f
        const val MIN_AREA = 0.02f
        const val SCORE = 0.8f
        private const val SLACK_M = 0.05f
        private const val MM_PER_M = 1000f
        private const val SAMPLE_STEP = 7

        /** The synthetic room's furniture ([io.github.sceneview.demo.demos.internal.RerunSyntheticRoom]), as COCO names it. */
        val ROOM: List<Pair<String, ScanBox>> = listOf(
            "couch" to box(-1.7f, -1.8f, -0.3f, -0.95f, 0.9f),
            "dining table" to box(0.3f, -0.3f, 1.3f, 0.4f, 0.76f),
            // A tall white box against a wall: COCO's nearest name for a cabinet.
            "refrigerator" to box(1.55f, 0.6f, 2f, 1.6f, 1.6f),
        )

        /** The upright box over the floor rectangle ([x0], [z0])–([x1], [z1]), [height] tall. */
        fun box(x0: Float, z0: Float, x1: Float, z1: Float, height: Float, baseY: Float = 0f) =
            ScanBox((x0 + x1) / 2, (z0 + z1) / 2, baseY, 0f, x1 - x0, z1 - z0, height)
    }
}

/** A pose as a [DebugPose] is written: for messages. */
internal fun DebugPose.describe() = "(%.2f, %.2f, %.2f)".format(x, y, z)

/** A box as a message reads it: centre, base, yaw in degrees, sizes. */
internal fun ScanBox.describe() = "[c (%.2f, %.2f) base %.2f yaw %.0f° %.2f × %.2f × %.2f]".format(
    centerX, centerZ, baseY, Math.toDegrees(yaw.toDouble()), length, depth, height,
)

/**
 * The synthetic room scanned once for every test that needs it: its depth frames, rendered from
 * every pose of the walk, and the cloud they fuse into, as the demo's capture fuses them.
 */
internal object SyntheticRoomScan {
    val poses: List<DebugPose> by lazy { RerunSyntheticRoom.poses() }
    val frames: List<DepthFrame> by lazy { poses.map { RerunSyntheticRoom.scene.render(it) } }
    val cloud: DenseCloud by lazy {
        val fusion = DenseFusion()
        frames.forEach { fusion.add(DepthBackProjection.project(it)) }
        fusion.cloud()
    }
    val objects: ScanObjects by lazy { ScanObjects.of(cloud) }

    /** Every [step]th frame as a keyframe, its photo found by the path `p<index>`. */
    fun keyframes(step: Int = 1): List<SceneKeyframe> = frames.indices.step(step).map { i ->
        SceneKeyframe(i, "p$i", i.toFloat(), SceneCamera(frames[i].pose, SceneTestKit.lensOf(frames[i])))
    }
}

/**
 * A 4 × 4 m room with a door cut in its north wall and a window in its east one, a larger room
 * round it seen through them; scanned from three stops, each a full turn, as the demo's capture
 * fuses its frames.
 */
internal object HoledRoomScan {
    val frames: List<DepthFrame> by lazy { RerunSyntheticRoom.poses(stops = STOPS).map { scene().render(it) } }
    val cloud: DenseCloud by lazy {
        val fusion = DenseFusion()
        frames.forEach { fusion.add(DepthBackProjection.project(it)) }
        fusion.cloud()
    }
    val objects: ScanObjects by lazy { ScanObjects.of(cloud) }

    /** Four walls 10 cm thick, the door and the window cut out, the larger room round them. */
    private fun scene(): SyntheticScene {
        fun wall(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) =
            SyntheticShape.Box(Vec3(x0, y0, z0), Vec3(x1, y1, z1)) { _, _, _ -> WALL }
        val outer = SyntheticShape.Room(Vec3(-OUTER, 0f, -OUTER), Vec3(OUTER, HEIGHT, OUTER)) { _, y, _ ->
            if (y < SURFACE) FLOOR else OUTSIDE
        }
        val shapes = listOf(
            outer,
            // North, z = −2: a door from x 0 to 0.9, 2.05 m high.
            wall(-R - T, 0f, -R - T, 0f, HEIGHT, -R),
            wall(0.9f, 0f, -R - T, R + T, HEIGHT, -R),
            wall(0f, 2.05f, -R - T, 0.9f, HEIGHT, -R),
            // South and west, whole.
            wall(-R - T, 0f, R, R + T, HEIGHT, R + T),
            wall(-R - T, 0f, -R - T, -R, HEIGHT, R + T),
            // East, x = 2: a window from z −0.6 to 0.6, 0.9 to 2.1 m up.
            wall(R, 0f, -R - T, R + T, HEIGHT, -0.6f),
            wall(R, 0f, 0.6f, R + T, HEIGHT, R + T),
            wall(R, 0f, -0.6f, R + T, 0.9f, 0.6f),
            wall(R, 2.1f, -0.6f, R + T, HEIGHT, 0.6f),
        )
        return SyntheticScene(shapes)
    }

    private const val R = 2f
    private const val T = 0.1f
    private const val OUTER = 3.5f
    private const val HEIGHT = 2.6f
    private const val SURFACE = 1e-3f
    private val STOPS = listOf(Vec3(0f, 1.4f, 0.2f), Vec3(-0.6f, 1.3f, 0.6f), Vec3(0.6f, 1.5f, -0.4f))

    // Scene content, not UI.
    private const val WALL = 0xFFE8E2D6.toInt()
    private const val FLOOR = 0xFFB08457.toInt()
    private const val OUTSIDE = 0xFF8FA58A.toInt()
}
