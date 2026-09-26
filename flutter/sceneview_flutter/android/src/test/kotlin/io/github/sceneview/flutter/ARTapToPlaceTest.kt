package io.github.sceneview.flutter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Android half of the AR tap-to-place wire format (#3780).
 *
 * The Dart side of the same contract is covered by
 * `test/ar_tap_to_place_test.dart`: `ARHitResult.fromMap` decodes what
 * [planeHitMap] writes, and `SceneViewController.placeModel` writes what
 * [parsePlaceModelRequest] reads. A key renamed on one side only would still
 * compile on both, and on a device the symptom would be a model sitting at the
 * world origin.
 */
class ARTapToPlaceTest {

    // ── planeTypeName ─────────────────────────────────────────────────────

    @Test
    fun `maps every ARCore plane type to the Dart plane type names`() {
        assertEquals("horizontal_upward", planeTypeName("HORIZONTAL_UPWARD_FACING"))
        assertEquals("horizontal_downward", planeTypeName("HORIZONTAL_DOWNWARD_FACING"))
        assertEquals("vertical", planeTypeName("VERTICAL"))
    }

    @Test
    fun `an unknown or missing plane type is unknown`() {
        assertEquals("unknown", planeTypeName(null))
        assertEquals("unknown", planeTypeName("SOMETHING_NEW"))
    }

    // ── planeHitMap ───────────────────────────────────────────────────────

    @Test
    fun `encodes the hit with the keys ARHitResult fromMap reads`() {
        val map = planeHitMap(
            id = "hit-3",
            translation = floatArrayOf(0.5f, -1.25f, -2f),
            rotation = floatArrayOf(0f, 0.7071068f, 0f, 0.7071068f),
            planeType = "horizontal_upward",
            distance = 1.5f,
        )
        assertEquals(
            setOf("id", "x", "y", "z", "qx", "qy", "qz", "qw", "planeType", "distance"),
            map.keys,
        )
        assertEquals("hit-3", map["id"])
        assertEquals(0.5, map["x"])
        assertEquals(-1.25, map["y"])
        assertEquals(-2.0, map["z"])
        assertEquals(0.0, map["qx"])
        assertEquals(0.7071068f.toDouble(), map["qy"])
        assertEquals(0.7071068f.toDouble(), map["qw"])
        assertEquals("horizontal_upward", map["planeType"])
        assertEquals(1.5, map["distance"])
    }

    @Test
    fun `sends numbers as doubles, the type Dart decodes to double`() {
        val map = planeHitMap("hit-0", FloatArray(3), floatArrayOf(0f, 0f, 0f, 1f), "vertical", 0f)
        listOf("x", "y", "z", "qx", "qy", "qz", "qw", "distance").forEach {
            assertTrue("$it should be a Double", map[it] is Double)
        }
    }

    // ── parsePlaceModelRequest ────────────────────────────────────────────

    private fun hit(vararg extra: Pair<String, Any?>) = mapOf(
        "id" to "hit-7",
        "x" to 0.1, "y" to -1.4, "z" to -0.8,
        "qx" to 0.0, "qy" to 0.0, "qz" to 0.0, "qw" to 1.0,
        "planeType" to "horizontal_upward", "distance" to 1.2,
    ) + extra

    private fun model(path: String? = "models/chair.glb", scale: Any? = 0.5) = mapOf(
        "modelPath" to path, "x" to 0.0, "y" to 0.0, "z" to 0.0, "scale" to scale,
        "rotationX" to 0.0, "rotationY" to 0.0, "rotationZ" to 0.0,
    )

    @Test
    fun `decodes the arguments placeModel sends`() {
        val request = parsePlaceModelRequest(
            mapOf(
                "hit" to hit(),
                "model" to model(),
                "editable" to true,
                "draggable" to true,
                "rotatable" to false,
                "scalable" to true,
            )
        )!!
        assertEquals("hit-7", request.hitId)
        assertEquals(0.1f, request.tx)
        assertEquals(-1.4f, request.ty)
        assertEquals(-0.8f, request.tz)
        assertEquals(1f, request.qw)
        assertEquals("models/chair.glb", request.modelPath)
        assertEquals(0.5f, request.size)
        assertTrue(request.canDrag)
        assertFalse(request.canRotate)
        assertTrue(request.canScale)
    }

    @Test
    fun `accepts integers where Dart wrote a whole number`() {
        // `ModelNode(scale: 1)` is an int in Dart, which arrives as Integer.
        val request = parsePlaceModelRequest(
            mapOf("hit" to hit("x" to 2, "qw" to 1L), "model" to model(scale = 1))
        )!!
        assertEquals(2f, request.tx)
        assertEquals(1f, request.qw)
        assertEquals(1f, request.size)
    }

    @Test
    fun `gesture flags default to on, and editable false turns them all off`() {
        val defaults = parsePlaceModelRequest(mapOf("hit" to hit(), "model" to model()))!!
        assertTrue(defaults.canDrag && defaults.canRotate && defaults.canScale)

        val locked = parsePlaceModelRequest(
            mapOf("hit" to hit(), "model" to model(), "editable" to false)
        )!!
        assertFalse(locked.canDrag)
        assertFalse(locked.canRotate)
        assertFalse(locked.canScale)
    }

    @Test
    fun `a zero or negative size falls back to one metre`() {
        assertEquals(1f, parsePlaceModelRequest(mapOf("hit" to hit(), "model" to model(scale = 0.0)))!!.size)
        assertEquals(1f, parsePlaceModelRequest(mapOf("hit" to hit(), "model" to model(scale = -2.0)))!!.size)
    }

    @Test
    fun `a missing hit or model path is rejected`() {
        assertNull(parsePlaceModelRequest(null))
        assertNull(parsePlaceModelRequest(mapOf("model" to model())))
        assertNull(parsePlaceModelRequest(mapOf("hit" to hit())))
        assertNull(parsePlaceModelRequest(mapOf("hit" to hit(), "model" to model(path = null))))
        assertNull(parsePlaceModelRequest(mapOf("hit" to hit(), "model" to model(path = " "))))
    }

    @Test
    fun `a hit without an id still decodes, for a pose-only anchor`() {
        val request = parsePlaceModelRequest(
            mapOf("hit" to hit() - "id", "model" to model())
        )!!
        assertNull(request.hitId)
    }

    // ── bottomCenterOffset ────────────────────────────────────────────────

    @Test
    fun `stands a centred model on the origin`() {
        // A 2 x 4 x 2 box centred on its origin, scaled to 1 m tall (s = 0.25).
        val offset = bottomCenterOffset(
            center = floatArrayOf(0f, 0f, 0f),
            halfExtent = floatArrayOf(1f, 2f, 1f),
            size = 1f,
        )
        assertArrayEquals(floatArrayOf(0f, 0.5f, 0f), offset, 1e-6f)
    }

    @Test
    fun `recentres a model whose origin is off its bounding box`() {
        // Box centred at (1, 3, -2) with half extents (1, 1, 1). Its largest
        // dimension is 2, so size 0.5 gives s = 0.25. The bottom sits at
        // y = 2, and must land on 0.
        val offset = bottomCenterOffset(
            center = floatArrayOf(1f, 3f, -2f),
            halfExtent = floatArrayOf(1f, 1f, 1f),
            size = 0.5f,
        )
        assertArrayEquals(floatArrayOf(-0.25f, -0.5f, 0.5f), offset, 1e-6f)
        // Bottom of the scaled box: (centerY - halfY) * s + offsetY == 0.
        assertEquals(0f, (3f - 1f) * 0.25f + offset[1], 1e-6f)
    }

    @Test
    fun `an empty bounding box does not produce NaN`() {
        val offset = bottomCenterOffset(FloatArray(3), FloatArray(3), size = 0.5f)
        offset.forEach { assertFalse(it.isNaN()) }
    }

    // ── RecentHits ────────────────────────────────────────────────────────

    @Test
    fun `keeps the most recent hits and forgets the oldest`() {
        val hits = RecentHits<String>(capacity = 2)
        val first = hits.put("a")
        val second = hits.put("b")
        val third = hits.put("c")
        assertEquals(2, hits.size)
        assertNull(hits[first])
        assertEquals("b", hits[second])
        assertEquals("c", hits[third])
        assertNull(hits[null])
    }

    @Test
    fun `hit ids are unique`() {
        val hits = RecentHits<Int>()
        val ids = (0 until 20).map { hits.put(it) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the pinch range brackets the placed size`() {
        assertTrue(1f in PLACED_SCALE_RANGE)
        assertEquals(0.25f, PLACED_SCALE_RANGE.start)
        assertEquals(4f, PLACED_SCALE_RANGE.endInclusive)
    }
}
