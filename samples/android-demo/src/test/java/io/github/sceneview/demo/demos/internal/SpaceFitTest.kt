package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A space's measure on scans built by hand: a cupboard under a sink with what frames it, a shelf
 * among others, a crate with no top. Each is sampled every 2 cm with the depth's few millimetres
 * of noise, turned and moved in the world as a real scan is.
 */
class SpaceFitTest {

    @Test
    fun `an empty cupboard is measured inside, to the scan's grid`() {
        val fit = fitOf(cupboard())
        assertEquals(WIDTH, fit.width, TOLERANCE)
        assertEquals(HEIGHT, fit.height, TOLERANCE)
        assertEquals(DEPTH, fit.depth, TOLERANCE)
        assertEquals(setOf(SpaceFace.Front), fit.open)
        assertTrue("obstacles ${fit.obstacles}", fit.obstacles.isEmpty())
    }

    @Test
    fun `the box that fits an empty cupboard is the cupboard, less its clearance`() {
        val fits = checkNotNull(fitOf(cupboard()).fits)
        assertEquals(WIDTH - 2 * SpaceFitter.CLEARANCE_M, fits.width, TOLERANCE)
        assertEquals(HEIGHT - SpaceFitter.CLEARANCE_M, fits.height, TOLERANCE)
        assertEquals(DEPTH - SpaceFitter.CLEARANCE_M, fits.depth, TOLERANCE)
        assertEquals(0f, fits.y, 0f)
    }

    @Test
    fun `the opening faces where the phone stood, or where the walls face without it`() {
        val scan = cupboard()
        val seen = fitOf(scan, eye = scan.eye)
        val guessed = fitOf(scan)
        assertEquals(seen.yawDegrees, guessed.yawDegrees, 1f)
        assertEquals(YAW_DEGREES, turn(seen.yawDegrees), 1f)
    }

    @Test
    fun `the frame's origin is the cupboard's back-left-bottom corner in the scan`() {
        val scan = cupboard()
        val fit = fitOf(scan)
        val corner = scan.world(0f, 0f, 0f)
        val origin = fit.world(0f, 0f, 0f)
        assertEquals(corner.x, origin.x, TOLERANCE)
        assertEquals(corner.y, origin.y, TOLERANCE)
        assertEquals(corner.z, origin.z, TOLERANCE)
        // …and its far corner, the front-right-top one.
        val far = scan.world(WIDTH, HEIGHT, DEPTH)
        val reached = fit.world(fit.width, fit.height, fit.depth)
        assertEquals(far.x, reached.x, 2 * TOLERANCE)
        assertEquals(far.z, reached.z, 2 * TOLERANCE)
    }

    @Test
    fun `a drain pipe is an obstacle, and the box that fits stays clear of it`() {
        val fit = fitOf(cupboard(pipe = true))
        assertEquals(WIDTH, fit.width, TOLERANCE)
        assertEquals(HEIGHT, fit.height, TOLERANCE)
        val pipe = fit.obstacles.single()
        assertEquals(PIPE.x, pipe.x, TOLERANCE)
        assertEquals(PIPE.z, pipe.z, TOLERANCE)
        assertEquals(PIPE.width, pipe.width, 2 * TOLERANCE)
        assertEquals(PIPE.y, pipe.y, 2 * TOLERANCE)
        val fits = checkNotNull(fit.fits)
        assertTrue("$fits meets $pipe", apart(fits, pipe))
        // The biggest is the one in front of the pipe, the whole width and height.
        assertEquals(WIDTH - 2 * SpaceFitter.CLEARANCE_M, fits.width, TOLERANCE)
        assertEquals(HEIGHT - SpaceFitter.CLEARANCE_M, fits.height, TOLERANCE)
        assertEquals(DEPTH, fits.z + fits.depth, TOLERANCE)
        // …a cell or two short of the pipe: the grid's own, and the one kept from an obstacle.
        val gap = fits.z - (PIPE.z + PIPE.depth)
        assertTrue("$gap m from the pipe", gap >= 0f && gap <= 3 * SpaceFitter.CELL_M)
    }

    @Test
    fun `a box that fits is never behind what blocks its way in`() {
        // A pipe across the whole width at mid depth, from the top down to 20 cm: nothing taller
        // than that passes under it to the back.
        val bar = SpaceBox(0f, 0.2f, 0.2f, WIDTH, HEIGHT - 0.2f, 0.06f)
        val fit = fitOf(cupboard(extra = bar))
        val fits = checkNotNull(fit.fits)
        val tall = fits.height > bar.y
        assertTrue("$fits passes the bar", !tall || fits.z >= bar.z + bar.depth)
    }

    @Test
    fun `a crate with no top is open there, as high as its walls`() {
        val fit = fitOf(cupboard(top = false))
        assertEquals(setOf(SpaceFace.Front, SpaceFace.Top), fit.open)
        assertEquals(HEIGHT, fit.height, 2 * TOLERANCE)
        assertEquals(fit.height, checkNotNull(fit.fits).height, TOLERANCE)
    }

    @Test
    fun `of several shelves, the one aimed at is the one measured`() {
        val fit = fitOf(cupboard(shelvesAround = true))
        assertEquals(HEIGHT, fit.height, TOLERANCE)
        assertEquals(WIDTH, fit.width, TOLERANCE)
        assertEquals(setOf(SpaceFace.Front), fit.open)
    }

    @Test
    fun `a floor alone, or a few points, is no space`() {
        val floor = Scan()
        floor.plane(0f, 0f, 0f, 2f, 2f, axis = 1, normal = 1f)
        assertNull(SpaceFitter.of(floor.cloud()))
        assertNull(SpaceFitter.of(DenseCloud.Empty))
        val bare = DenseCloud(FloatArray(900), IntArray(300))
        assertNull(SpaceFitter.of(bare))
    }

    @Test
    fun `the answer is one JSON object in metres, in the space's own frame`() {
        val fit = fitOf(cupboard(pipe = true))
        val text = SpaceAnswer.json(fit, "Under the sink")
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(1, root.getValue("v").jsonPrimitive.content.toInt())
        assertEquals("space", root.getValue("kind").jsonPrimitive.content)
        assertEquals("Under the sink", root.getValue("label").jsonPrimitive.content)
        assertEquals("m", root.getValue("units").jsonPrimitive.content)
        assertEquals("scan", root.getValue("dimensionSource").jsonPrimitive.content)
        assertEquals(SpaceAnswer.FRAME, root.getValue("frame").jsonPrimitive.content)
        val size = root.getValue("interior").jsonObject.getValue("sizeM").jsonArray.map { it.jsonPrimitive.content.toFloat() }
        // Width, height, depth: X, Y, Z.
        assertEquals(WIDTH, size[0], TOLERANCE)
        assertEquals(HEIGHT, size[1], TOLERANCE)
        assertEquals(DEPTH, size[2], TOLERANCE)
        assertEquals(listOf("front"), root.getValue("open").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, root.getValue("obstacles").jsonArray.size)
        val fits = root.getValue("fits").jsonObject
        assertEquals(3, (fits.getValue("minM") as JsonArray).size)
        assertEquals(3, (fits.getValue("sizeM") as JsonArray).size)
        // Millimetres, written as they read.
        assertTrue(text, Regex("""\d\.\d{4,}""").find(text) == null)
    }

    @Test
    fun `a space nothing fits says so`() {
        val none = SpaceFit(0.04f, 0.04f, 0.04f, setOf(SpaceFace.Front), emptyList(), null, 0f, 0f, 0f, 0f)
        val root = SpaceAnswer.tree(none, "Slot")
        assertEquals(JsonNull, root.getValue("fits"))
        assertTrue((root.getValue("obstacles") as JsonArray).isEmpty())
        assertNotNull(root.getValue("interior") as? JsonObject)
    }

    private fun fitOf(scan: Scan, eye: Vec3? = null): SpaceFit = checkNotNull(SpaceFitter.of(scan.cloud(), eye = eye)) { "no space" }

    /** [degrees] folded into a quarter turn: the walls square the frame, the opening picks its side. */
    private fun turn(degrees: Float) = ((degrees % 360f) + 360f) % 360f

    /** Whether [a] and [b] do not overlap. */
    private fun apart(a: SpaceBox, b: SpaceBox): Boolean =
        a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y ||
            a.z + a.depth <= b.z || b.z + b.depth <= a.z

    /**
     * A cupboard [WIDTH] × [HEIGHT] × [DEPTH] inside, as its scan: its five inner faces, the two
     * door fronts either side of the opening and the room's floor before it, a plinth lower.
     * With a [pipe], [PIPE] hangs from its top; [extra] is one more thing inside; without a [top]
     * it is a crate; with [shelvesAround] it is one shelf of a bookcase, part of the next ones seen.
     */
    private fun cupboard(
        pipe: Boolean = false,
        extra: SpaceBox? = null,
        top: Boolean = true,
        shelvesAround: Boolean = false,
    ): Scan = Scan().apply {
        plane(0f, 0f, 0f, WIDTH, HEIGHT, axis = 2, normal = 1f) // back
        plane(0f, 0f, 0f, HEIGHT, DEPTH, axis = 0, normal = 1f) // left
        plane(WIDTH, 0f, 0f, HEIGHT, DEPTH, axis = 0, normal = -1f) // right
        plane(0f, 0f, 0f, WIDTH, DEPTH, axis = 1, normal = 1f) // bottom
        if (top) plane(0f, HEIGHT, 0f, WIDTH, DEPTH, axis = 1, normal = -1f)
        // The fronts beside the opening, and the floor in front, 10 cm lower.
        plane(-0.3f, 0f, DEPTH, 0.3f, HEIGHT, axis = 2, normal = 1f)
        plane(WIDTH, 0f, DEPTH, 0.3f, HEIGHT, axis = 2, normal = 1f)
        plane(-0.3f, -0.1f, DEPTH, WIDTH + 0.6f, 0.4f, axis = 1, normal = 1f)
        if (pipe) box(PIPE)
        extra?.let(::box)
        if (shelvesAround) {
            // The shelf above and the one below: their back in part, and the board between.
            plane(0f, HEIGHT + BOARD, 0f, WIDTH, 0.15f, axis = 2, normal = 1f)
            plane(0f, HEIGHT + BOARD, 0f, WIDTH, DEPTH * 0.6f, axis = 1, normal = 1f)
            plane(0f, -BOARD - 0.15f, 0f, WIDTH, 0.15f, axis = 2, normal = 1f)
            plane(0f, -BOARD, 0f, WIDTH, DEPTH * 0.6f, axis = 1, normal = -1f)
        }
    }

    /** Points of a scan, laid in a space's frame and written in the world, turned and moved. */
    private class Scan {
        private val positions = ArrayList<Float>()
        private val normals = ArrayList<Float>()
        private val random = Random(SEED)
        private val yaw = Math.toRadians(YAW_DEGREES.toDouble())

        /** Where the phone stood: a metre before the opening, at mid height. */
        val eye: Vec3 get() = world(WIDTH / 2f, HEIGHT / 2f, DEPTH + 1f)

        fun world(x: Float, y: Float, z: Float): Vec3 {
            val c = cos(yaw).toFloat()
            val s = sin(yaw).toFloat()
            return Vec3(ORIGIN.x + x * c + z * s, ORIGIN.y + y, ORIGIN.z - x * s + z * c)
        }

        /**
         * A rectangle from ([x], [y], [z]) across the two axes other than [axis], [a] then [b]
         * metres, its normal [normal] along [axis]: a point every 2 cm, a few millimetres off.
         */
        @Suppress("LongParameterList")
        fun plane(x: Float, y: Float, z: Float, a: Float, b: Float, axis: Int, normal: Float) {
            var i = STEP / 2f
            while (i < a) {
                var j = STEP / 2f
                while (j < b) {
                    val noise = (random.nextFloat() - 0.5f) * 2f * NOISE
                    val at = when (axis) {
                        0 -> world(x + noise, y + i, z + j)
                        1 -> world(x + i, y + noise, z + j)
                        else -> world(x + i, y + j, z + noise)
                    }
                    positions += listOf(at.x, at.y, at.z)
                    val c = cos(yaw).toFloat()
                    val s = sin(yaw).toFloat()
                    normals += when (axis) {
                        0 -> listOf(normal * c, 0f, -normal * s)
                        1 -> listOf(0f, normal, 0f)
                        else -> listOf(normal * s, 0f, normal * c)
                    }
                    j += STEP
                }
                i += STEP
            }
        }

        /** The faces of [of] a phone at the opening sees: its front, its sides and its underside. */
        fun box(of: SpaceBox) {
            plane(of.x, of.y, of.z + of.depth, of.width, of.height, axis = 2, normal = 1f)
            plane(of.x, of.y, of.z, of.height, of.depth, axis = 0, normal = -1f)
            plane(of.x + of.width, of.y, of.z, of.height, of.depth, axis = 0, normal = 1f)
            plane(of.x, of.y, of.z, of.width, of.depth, axis = 1, normal = -1f)
        }

        fun cloud() = DenseCloud(positions.toFloatArray(), IntArray(positions.size / 3) { WHITE }, normals.toFloatArray())
    }

    private companion object {
        const val WIDTH = 0.6f
        const val HEIGHT = 0.56f
        const val DEPTH = 0.5f

        /** The pipe: 8 cm square, from 24 cm up to the top, 10 cm from the back. */
        val PIPE = SpaceBox(0.26f, 0.24f, 0.1f, 0.08f, HEIGHT - 0.24f, 0.08f)
        const val BOARD = 0.02f
        const val YAW_DEGREES = 25f
        val ORIGIN = Vec3(1.2f, -0.9f, -0.4f)
        const val STEP = 0.02f
        const val NOISE = 0.004f
        const val TOLERANCE = 0.02f
        const val SEED = 20261114
        const val WHITE = -1
    }
}
