package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picking anchors, scale-independent geometry and camera-facing rotations, without Filament. */
class CalloutLayoutTest {
    private val layout = CalloutLayout
    private val epsilon = 1e-4f

    @Test
    fun `rocket stands on the floor and is one metre tall`() {
        val engine = layout.partPosition(RocketPart.Engine)
        val body = layout.partPosition(RocketPart.Body)
        val nose = layout.partPosition(RocketPart.Nose)
        assertEquals(0f, engine.y - layout.ENGINE_HEIGHT / 2f, epsilon)
        assertEquals(engine.y + layout.ENGINE_HEIGHT / 2f, body.y - layout.BODY_HEIGHT / 2f, epsilon)
        assertEquals(body.y + layout.BODY_HEIGHT / 2f, nose.y - layout.NOSE_HEIGHT / 2f, epsilon)
        assertEquals(1f, nose.y + layout.NOSE_HEIGHT / 2f, epsilon)
        assertEquals(5, RocketPart.entries.size)
        assertTrue(RocketPart.entries.all { it.originalMaterial in 0..3 })
    }

    @Test
    fun `three fins have equal spacing and touch the floor`() {
        val fins = layout.FIN_YAWS.map(layout::finPosition)
        assertEquals(3, fins.size)
        fins.forEach { assertEquals(0f, it.y - layout.FIN_SIZE.y / 2f, epsilon) }
        val distances = fins.indices.map { i ->
            val a = fins[i]
            val b = fins[(i + 1) % fins.size]
            hypot(a.x - b.x, a.z - b.z)
        }
        distances.forEach { assertEquals(distances.first(), it, epsilon) }
    }

    @Test
    fun `window protrudes from the front of the body`() {
        val window = layout.partPosition(RocketPart.Window)
        val depth = layout.WINDOW_RADIUS * layout.WINDOW_SCALE.z
        assertTrue(window.z - depth < layout.BODY_RADIUS)
        assertTrue(window.z + depth > layout.BODY_RADIUS)
    }

    @Test
    fun `selection anchors to the viewer side at every object yaw and camera bearing`() {
        for (yaw in listOf(0f, 90f, 180f, 270f)) {
            for (bearing in listOf(0f, 60f, 180f, 240f)) {
                val camera = layout.rotateY(Position(0f, 1f, 3f), bearing)
                RocketPart.entries.forEach { part ->
                    val local = layout.cardAnchor(part, camera, yaw)
                    val world = layout.rotateY(local, yaw)
                    assertTrue(world.x * camera.x + world.z * camera.z > 0f)
                    assertEquals(layout.partPosition(part).y.coerceIn(0.24f, 0.82f), local.y, epsilon)
                }
            }
        }
    }

    @Test
    fun `attached card rides its parent but its front still faces the camera`() {
        val camera = Position(0f, 1f, 3f)
        val anchor = layout.cardAnchor(RocketPart.Body, camera, 0f)
        for (yaw in listOf(0f, 45f, 90f, 180f, 270f)) {
            val world = layout.rotateY(anchor, yaw)
            assertEquals(hypot(anchor.x, anchor.z), hypot(world.x, world.z), epsilon)
            val facing = layout.billboardYawDegrees(world, camera, yaw) + yaw
            val radians = Math.toRadians(facing.toDouble())
            val dx = camera.x - world.x
            val dz = camera.z - world.z
            val length = hypot(dx, dz)
            assertEquals(dx / length, sin(radians).toFloat(), epsilon)
            assertEquals(dz / length, cos(radians).toFloat(), epsilon)
        }
        assertEquals(0f, layout.billboardYawDegrees(camera, camera, 90f), epsilon)
    }

    @Test
    fun `home eye is the requested distance from the target`() {
        for (distance in listOf(0f, 1f, 3f, 8f)) {
            val offset = layout.cameraHome(distance)
            assertEquals(distance.coerceAtLeast(0.6f), hypot(offset.y, offset.z), epsilon)
            assertTrue(offset.y > 0f)
            assertTrue(offset.z > 0f)
        }
    }

    @Test
    fun `portrait camera retreats to keep the whole gallery inside the narrow viewport`() {
        val portrait = layout.cameraDistance(layout.MEDIA_EXTENT, aspect = 0.5f)
        val landscape = layout.cameraDistance(layout.MEDIA_EXTENT, aspect = 2.4f)
        assertTrue(portrait.isFinite() && landscape.isFinite())
        assertTrue(portrait > landscape && landscape > 0f)
    }

    @Test
    fun `gallery surfaces face inward and captions clear their exhibits`() {
        val gallery = layout.GALLERY
        assertEquals(3, gallery.size)
        assertTrue(gallery[0].position.x < 0f && gallery[0].yaw > 0f)
        assertTrue(gallery[1].position.x > 0f && gallery[1].yaw < 0f)
        gallery.forEach {
            val captionTop = layout.captionPosition(it).y + layout.CAPTION_SIZE.y / 2f
            assertTrue(captionTop < it.position.y - it.size.y / 2f)
            val frame = layout.frameSize(it.size)
            assertTrue(frame.x > it.size.x && frame.y > it.size.y)
            assertTrue(layout.CONTENT_OFFSET.z > frame.z / 2f)
        }
        val badgeCaptionBottom = layout.captionPosition(gallery[2]).y - layout.CAPTION_SIZE.y / 2f
        assertTrue(gallery.take(2).all { it.position.y + it.size.y / 2f < badgeCaptionBottom })
    }

    @Test
    fun `stationary camera and nonpositive time never advance state`() {
        val eye = Position(0f, 1f, 3f)
        assertFalse(layout.movedPerceptibly(eye, eye.copy(x = 0.0005f)))
        assertTrue(layout.movedPerceptibly(eye, eye.copy(x = 0.002f)))
        assertEquals(42f, layout.nextTurntableYaw(42f, 0L), 0f)
        assertEquals(42f, layout.nextTurntableYaw(42f, -1L), 0f)
        assertEquals(15f, layout.nextTurntableYaw(0f, 1_000_000_000L), epsilon)
        assertEquals(-175f, layout.nextTurntableYaw(170f, 1_000_000_000L), epsilon)
    }
}
