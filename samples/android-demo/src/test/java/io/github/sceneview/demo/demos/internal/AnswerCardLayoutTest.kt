package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Point & Ask's answer-card layout (#4071): inside the safe area, never on top of each other. */
class AnswerCardLayoutTest {

    private val safe = SafeArea(left = 24f, top = 200f, right = 1056f, bottom = 2000f)
    private val gap = 16f

    private fun card(id: Int, x: Float, y: Float, w: Float = 400f, h: Float = 250f) =
        CardRequest(id, x, y, w, h)

    private data class Rect(val l: Float, val t: Float, val r: Float, val b: Float)

    private fun rectOf(request: CardRequest, placement: CardPlacement) = Rect(
        request.centerX + placement.offsetX - request.width / 2f,
        request.centerY + placement.offsetY - request.height / 2f,
        request.centerX + placement.offsetX + request.width / 2f,
        request.centerY + placement.offsetY + request.height / 2f,
    )

    private fun assertInside(rect: Rect) {
        val eps = 0.01f
        assertTrue("$rect leaves the safe area $safe", rect.l >= safe.left - eps && rect.r <= safe.right + eps)
        assertTrue("$rect leaves the safe area $safe", rect.t >= safe.top - eps && rect.b <= safe.bottom + eps)
    }

    private fun assertApart(a: Rect, b: Rect) {
        // The layout allows half a pixel of float slack on the gap.
        val eps = 0.51f
        val apart = a.r + gap <= b.l + eps || b.r + gap <= a.l + eps ||
            a.b + gap <= b.t + eps || b.b + gap <= a.t + eps
        assertTrue("$a and $b are closer than $gap px", apart)
    }

    @Test fun `a card with room keeps its anchored position`() {
        val placement = layoutAnswerCards(listOf(card(1, 540f, 1000f)), safe, gap).single()
        assertEquals(CardPlacement(1, 0f, 0f, visible = true), placement)
    }

    @Test fun `a card pinned near an edge is pulled back inside the safe area`() {
        // The #4071 recording: an answer pinned at the right edge ran off the screen.
        val requests = listOf(card(1, 1000f, 150f))
        val placement = layoutAnswerCards(requests, safe, gap).single()
        val rect = rectOf(requests.single(), placement)
        assertInside(rect)
        assertEquals(safe.right, rect.r, 0.01f)
        assertEquals(safe.top, rect.t, 0.01f)
    }

    @Test fun `a card wider than the safe area is centred on that axis`() {
        val requests = listOf(card(1, 100f, 1000f, w = 2000f))
        val placement = layoutAnswerCards(requests, safe, gap).single()
        assertEquals((safe.left + safe.right) / 2f, 100f + placement.offsetX, 0.01f)
        assertTrue(placement.visible)
    }

    @Test fun `two answers pinned close together are pushed apart and the first does not move`() {
        // The #4071 recording: two objects a hand's width apart, one card over the other.
        val requests = listOf(card(1, 540f, 1000f), card(2, 600f, 1060f))
        val (first, second) = layoutAnswerCards(requests, safe, gap)
        assertEquals(0f, first.offsetX, 0f)
        assertEquals(0f, first.offsetY, 0f)
        assertTrue(second.visible)
        assertApart(rectOf(requests[0], first), rectOf(requests[1], second))
        assertInside(rectOf(requests[1], second))
        // The nearest free spot is straight below: the card moves the least it can.
        assertEquals(0f, second.offsetX, 0.01f)
        assertEquals(1000f + 125f + gap + 125f, 1060f + second.offsetY, 0.01f)
    }

    @Test fun `a card that cannot fit anywhere is hidden, never drawn over another`() {
        val tiny = SafeArea(0f, 0f, 500f, 300f)
        val requests = listOf(card(1, 250f, 150f), card(2, 260f, 160f))
        val (first, second) = layoutAnswerCards(requests, tiny, gap)
        assertTrue(first.visible)
        assertFalse(second.visible)
    }

    @Test fun `the same input lays out the same way`() {
        val requests = listOf(card(1, 540f, 1000f), card(2, 560f, 1010f), card(3, 520f, 990f))
        assertEquals(layoutAnswerCards(requests, safe, gap), layoutAnswerCards(requests, safe, gap))
    }

    @Test fun `random piles of cards stay inside and apart, and the first is always shown`() {
        val random = Random(4071)
        repeat(500) {
            val requests = List(random.nextInt(1, 9)) { id ->
                card(
                    id,
                    x = random.nextFloat() * 1300f - 100f,
                    y = random.nextFloat() * 2400f - 100f,
                    w = 300f + random.nextFloat() * 400f,
                    h = 150f + random.nextFloat() * 250f,
                )
            }
            val placements = layoutAnswerCards(requests, safe, gap)
            assertEquals(requests.map { it.id }, placements.map { it.id })
            assertTrue(placements.first().visible)
            val shown = requests.zip(placements).filter { it.second.visible }.map { (r, p) -> rectOf(r, p) }
            shown.forEach(::assertInside)
            for (i in shown.indices) for (j in i + 1 until shown.size) assertApart(shown[i], shown[j])
        }
    }

    // ── ScreenProjection ─────────────────────────────────────────────────────────────────────

    /** Column-major view matrix of a camera at [eye], turned [yawDeg] about world +Y. */
    private fun viewMatrix(eye: FloatArray, yawDeg: Float): FloatArray {
        val a = Math.toRadians(yawDeg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        // Camera-to-world rotation Ry(yaw), row-major; the view rotation is its transpose.
        val r = arrayOf(floatArrayOf(c, 0f, s), floatArrayOf(0f, 1f, 0f), floatArrayOf(-s, 0f, c))
        val m = FloatArray(16)
        for (row in 0 until 3) for (col in 0 until 3) m[col * 4 + row] = r[col][row]
        for (row in 0 until 3) {
            m[12 + row] = -(0 until 3).sumOf { k -> (r[k][row] * eye[k]).toDouble() }.toFloat()
        }
        m[15] = 1f
        return m
    }

    /** Column-major OpenGL perspective, as ARCore returns it. */
    private fun perspective(fx: Float, fy: Float, near: Float = 0.1f, far: Float = 100f): FloatArray =
        FloatArray(16).also {
            it[0] = fx
            it[5] = fy
            it[10] = -(far + near) / (far - near)
            it[11] = -1f
            it[14] = -2f * far * near / (far - near)
        }

    @Test fun `a point straight ahead projects to the screen centre at its depth`() {
        val view = viewMatrix(floatArrayOf(0f, 0f, 0f), 0f)
        val projection = ScreenProjection(view, perspective(1.5f, 2f), 1080f, 2400f)
        val point = projection.project(0f, 0f, -2f)
        assertNotNull(point)
        assertEquals(540f, point!!.x, 0.01f)
        assertEquals(1200f, point.y, 0.01f)
        assertEquals(2f, point.depth, 1e-5f)
        assertEquals(null, projection.project(0f, 0f, 2f))
    }

    @Test fun `a world offset from a screen offset moves the point by exactly that on screen`() {
        for (yaw in listOf(0f, 37f, 90f, -140f)) {
            val eye = floatArrayOf(0.4f, 1.3f, -0.7f)
            val projection = ScreenProjection(viewMatrix(eye, yaw), perspective(1.5f, 2f), 1080f, 2400f)
            // A point 1.8 m in front of this camera.
            val a = Math.toRadians(yaw.toDouble())
            val world = floatArrayOf(
                eye[0] - 1.8f * sin(a).toFloat() + 0.1f,
                eye[1] - 0.2f,
                eye[2] - 1.8f * cos(a).toFloat(),
            )
            val before = projection.project(world[0], world[1], world[2])!!
            val offset = projection.screenOffsetToWorld(-120f, 75f, before.depth)
            val after = projection.project(world[0] + offset[0], world[1] + offset[1], world[2] + offset[2])!!
            assertEquals("yaw $yaw", before.x - 120f, after.x, 0.05f)
            assertEquals("yaw $yaw", before.y + 75f, after.y, 0.05f)
            assertEquals("yaw $yaw keeps depth", before.depth, after.depth, 1e-4f)
        }
    }
}
