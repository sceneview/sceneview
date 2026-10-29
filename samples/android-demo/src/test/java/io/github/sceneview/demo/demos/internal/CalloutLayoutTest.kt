package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import kotlin.math.abs
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

    /** The card on a portrait phone: 128 x 160 dp at about 300 dp to the metre. */
    private val portraitCard = Size(0.43f, 0.53f, 0f)

    @Test
    fun `rocket stands on the floor and is one metre tall`() {
        val engine = layout.partPosition(RocketPart.Engine)
        val body = layout.partPosition(RocketPart.Body)
        val nose = layout.partPosition(RocketPart.Nose)
        assertEquals(0f, engine.y - layout.ENGINE_HEIGHT / 2f, epsilon)
        // The nozzle is a cone: its tip is buried in the body, its flare is narrower than the body.
        assertTrue(engine.y + layout.ENGINE_HEIGHT / 2f > body.y - layout.BODY_HEIGHT / 2f)
        assertTrue(layout.ENGINE_RADIUS < layout.BODY_RADIUS)
        assertEquals(body.y + layout.BODY_HEIGHT / 2f, nose.y - layout.NOSE_HEIGHT / 2f, epsilon)
        assertEquals(1f, nose.y + layout.NOSE_HEIGHT / 2f, epsilon)
        assertEquals(5, RocketPart.entries.size)
        assertTrue(RocketPart.entries.all { it.originalMaterial in 0..3 })
    }

    @Test
    fun `three fins have equal spacing, lean on the body and leave the front clear`() {
        val fins = layout.FIN_YAWS.map(layout::finPosition)
        assertEquals(3, fins.size)
        val distances = fins.indices.map { i ->
            val a = fins[i]
            val b = fins[(i + 1) % fins.size]
            hypot(a.x - b.x, a.z - b.z)
        }
        distances.forEach { assertEquals(distances.first(), it, epsilon) }
        // In the fin's own frame: Y up the slab, Z out from the body, tilted about X.
        val tilt = Math.toRadians(layout.FIN_TILT_DEGREES.toDouble())
        fun corner(up: Float, out: Float): Pair<Float, Float> {
            val y = up * layout.FIN_SIZE.y / 2f
            val z = out * layout.FIN_SIZE.z / 2f
            return (layout.FIN_CENTER.y + y * cos(tilt) - z * sin(tilt)).toFloat() to
                (layout.FIN_CENTER.z + y * sin(tilt) + z * cos(tilt)).toFloat()
        }
        val (rootY, rootZ) = corner(up = 1f, out = -1f)
        val (tipY, tipZ) = corner(up = -1f, out = 1f)
        assertTrue("the root is inside the body", rootZ < layout.BODY_RADIUS)
        assertTrue("the root is on the body, above the nozzle", rootY > layout.ENGINE_HEIGHT)
        assertTrue("the tip sweeps out past the root", tipZ > rootZ + layout.FIN_SIZE.z)
        assertTrue("the tip rests on the floor", tipY in 0f..0.05f)
        // The window is at yaw 0: no fin within 45 degrees of it.
        assertTrue(layout.FIN_YAWS.all { abs(layout.normalizeDegrees(it)) >= 45f })
    }

    @Test
    fun `window protrudes from the front of the body`() {
        val window = layout.partPosition(RocketPart.Window)
        val depth = layout.WINDOW_RADIUS * layout.WINDOW_SCALE.z
        assertTrue(window.z - depth < layout.BODY_RADIUS)
        assertTrue(window.z + depth > layout.BODY_RADIUS)
    }

    @Test
    fun `selection anchors to the viewer's right at every object yaw and camera bearing`() {
        val reach = layout.BODY_RADIUS + layout.CARD_GAP + portraitCard.x / 2f
        for (yaw in listOf(0f, 90f, 180f, 270f)) {
            for (bearing in listOf(0f, 60f, 180f, 240f)) {
                val camera = layout.rotateY(Position(0f, 1f, 3f), bearing)
                val right = layout.rotateY(Position(x = 1f), bearing)
                RocketPart.entries.forEach { part ->
                    val local = layout.cardAnchor(part, camera, yaw, portraitCard)
                    val world = layout.rotateY(local, yaw)
                    // Beside the rocket as the viewer sees it, at the rocket's own depth.
                    assertEquals(reach, world.x * right.x + world.z * right.z, epsilon)
                    assertEquals(0f, world.x * camera.x + world.z * camera.z, epsilon)
                }
            }
        }
    }

    @Test
    fun `card clears the body and the fins and stays under the nose while it can`() {
        val camera = Position(0f, 1f, 3f)
        val room = 1f + layout.CARD_GAP - layout.FIN_TOP
        for (card in listOf(portraitCard, Size(0.46f, 0.58f, 0f))) {
            assertTrue(card.y < room)
            RocketPart.entries.forEach { part ->
                val anchor = layout.cardAnchor(part, camera, 0f, card)
                assertTrue(anchor.x - card.x / 2f > layout.BODY_RADIUS)
                assertTrue(anchor.y - card.y / 2f >= layout.FIN_TOP - epsilon)
                assertTrue(anchor.y + card.y / 2f <= 1f + layout.CARD_GAP + epsilon)
            }
            // A low part and a high part keep distinct anchors while the card leaves them room.
            val low = layout.cardAnchor(RocketPart.Engine, camera, 0f, card).y
            val high = layout.cardAnchor(RocketPart.Nose, camera, 0f, card).y
            assertTrue(high > low)
        }
        // Sideways the card is as tall as the rocket: it stands outside the fins, centred on it,
        // instead of climbing over the nose and out of the strip.
        for (card in listOf(Size(0.71f, 0.89f, 0f), Size(0.9f, 1.4f, 0f))) {
            RocketPart.entries.forEach { part ->
                val anchor = layout.cardAnchor(part, camera, 0f, card)
                assertTrue(anchor.x - card.x / 2f > layout.INSPECT_EXTENT.x / 2f)
                assertEquals(layout.INSPECT_TARGET.y, anchor.y, epsilon)
            }
        }
        // The slab's top corner really is under that line.
        val tilt = Math.toRadians(layout.FIN_TILT_DEGREES.toDouble())
        val finTop = layout.FIN_CENTER.y + layout.FIN_SIZE.y / 2f * cos(tilt) +
            layout.FIN_SIZE.z / 2f * abs(sin(tilt))
        assertTrue(finTop <= layout.FIN_TOP)
    }

    @Test
    fun `a card dp is a screen dp at the home distance, in both orientations`() {
        val cardDp = 128f
        val marginDp = 16f
        // Free areas: a Pixel 7a upright and sideways, then a 360 dp phone upright.
        for ((width, height) in listOf(427f to 640f, 900f to 300f, 360f to 560f)) {
            val distance = layout.inspectDistance(width / height, width, cardDp, marginDp)
            val metersPerDp = layout.metersPerDp(distance, height)
            // The rocket is inside the free area.
            assertTrue(layout.INSPECT_EXTENT.x / metersPerDp <= width)
            assertTrue(layout.INSPECT_EXTENT.y / metersPerDp <= height)
            // The card, one dp for one dp, ends before the screen does.
            val cardStart = width / 2f + (layout.BODY_RADIUS + layout.CARD_GAP) / metersPerDp
            assertTrue(cardStart + cardDp + marginDp <= width + 0.5f)
            // Twice the distance, twice the metres under one dp.
            assertEquals(metersPerDp * 2f, layout.metersPerDp(distance * 2f, height), epsilon)
        }
        // Upright on a Pixel 7a, the one-metre rocket is at least 350 dp of a 952 dp screen.
        val upright = layout.inspectDistance(427f / 640f, 427f, cardDp, marginDp)
        assertTrue(1f / layout.metersPerDp(upright, 640f) > 350f)
        assertEquals(0f, layout.metersPerDp(upright, 0f), 0f)
        // The card beside the nose is nearer to the lowered eye, so drawn larger: it still fits.
        val lift = (1f + layout.CARD_GAP - layout.INSPECT_TARGET.y) *
            sin(Math.toRadians(layout.ELEVATION_DEGREES.toDouble())).toFloat()
        val nearer = upright / (upright - lift)
        val reach = (layout.BODY_RADIUS + layout.CARD_GAP) / layout.metersPerDp(upright, 640f) + cardDp
        assertTrue(reach * nearer + marginDp <= 427f / 2f + 0.5f)
        // Sideways, there is room to spare: the rocket alone decides.
        assertEquals(layout.cameraDistance(layout.INSPECT_EXTENT, 3f),
            layout.inspectDistance(3f, 900f, cardDp, marginDp), epsilon)
        // Too narrow for a card beside the body: the retreat is capped, the card overlaps.
        val fit = layout.cameraDistance(layout.INSPECT_EXTENT, 0.5f)
        assertEquals(fit * layout.MAX_CARD_RETREAT, layout.inspectDistance(0.5f, 300f, cardDp, marginDp), epsilon)
        assertEquals(fit * layout.MAX_CARD_RETREAT, layout.inspectDistance(0.5f, 240f, cardDp, marginDp), epsilon)
    }

    @Test
    fun `attached card rides its parent but its front still faces the camera`() {
        val camera = Position(0f, 1f, 3f)
        val anchor = layout.cardAnchor(RocketPart.Body, camera, 0f, portraitCard)
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
        // Leaning back by the eye's elevation makes the card parallel to the image plane.
        val home = layout.cameraHome(3f) + layout.INSPECT_TARGET
        assertEquals(-layout.ELEVATION_DEGREES, layout.billboardPitchDegrees(layout.INSPECT_TARGET, home), 0.01f)
        assertEquals(0f, layout.billboardPitchDegrees(layout.INSPECT_TARGET, Position(0f, 0.5f, 3f)), epsilon)
        assertEquals(0f, layout.billboardPitchDegrees(layout.INSPECT_TARGET, Position(0f, 4f, 0f)), epsilon)
    }

    @Test
    fun `the card is scaled for the distance the eye is really placed at`() {
        val freeHeight = 640f
        // 0.05 is the smallest distance a deep link may ask for; the home never comes that close.
        for (requested in listOf(0.05f, 0.3f, 0.6f, 2f)) {
            val eye = layout.cameraHome(requested)
            val applied = hypot(eye.y, eye.z)
            assertEquals(applied, layout.homeDistance(requested), epsilon)
            // Bounding first changes nothing to where the eye goes.
            assertEquals(eye, layout.cameraHome(layout.homeDistance(requested)))
            // One card dp is one screen dp at the eye's real distance, not at the requested one.
            assertEquals(
                layout.metersPerDp(applied, freeHeight),
                layout.metersPerDp(layout.homeDistance(requested), freeHeight),
                1e-7f,
            )
        }
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
    fun `portrait camera retreats to keep the whole showcase inside the narrow viewport`() {
        val portrait = layout.cameraDistance(layout.SHOWCASE_EXTENT, aspect = 0.5f)
        val landscape = layout.cameraDistance(layout.SHOWCASE_EXTENT, aspect = 2.4f)
        assertTrue(portrait.isFinite() && landscape.isFinite())
        assertTrue(portrait > landscape && landscape > 0f)
    }

    @Test
    fun `the wall hangs behind and above the rocket, frames turned in, captions under them`() {
        val wall = layout.SHOWCASE
        assertEquals(3, wall.size)
        assertTrue(wall[0].position.x < 0f && wall[0].yaw > 0f)
        assertTrue(wall[1].position.x > 0f && wall[1].yaw < 0f)
        val frames = wall.take(2).map { layout.frameSize(it.size) }
        wall.take(2).forEachIndexed { index, exhibit ->
            // Behind the rocket, and every frame's bottom edge over the nose (1 m).
            assertTrue(exhibit.position.z < -layout.INSPECT_EXTENT.z / 2f)
            assertTrue(exhibit.position.y - frames[index].y / 2f > 1f)
            val caption = layout.SHOWCASE_CAPTION_SIZE
            assertTrue(exhibit.caption.y + caption.y / 2f < exhibit.position.y - frames[index].y / 2f)
            // Above the nose cone in front of them.
            assertTrue(exhibit.caption.y - caption.y / 2f > 1f)
            assertEquals(exhibit.position.x, exhibit.caption.x, epsilon)
        }
        assertTrue(wall[0].position.x + frames[0].x / 2f < wall[1].position.x - frames[1].x / 2f)
        // The badge floats over both frames.
        assertTrue(wall.take(2).indices.all {
            wall[it].position.y + frames[it].y / 2f < wall[2].position.y - wall[2].size.y / 2f
        })
        // TextNode rasters 4:1; any other quad stretches the glyphs.
        assertEquals(4f, layout.SHOWCASE_CAPTION_SIZE.x / layout.SHOWCASE_CAPTION_SIZE.y, epsilon)
        // Top to bottom, everything is inside the box the camera fits.
        val top = wall[2].position.y + wall[2].size.y / 2f
        assertTrue(top <= layout.SHOWCASE_TARGET.y + layout.SHOWCASE_EXTENT.y / 2f + epsilon)
        assertTrue(0f >= layout.SHOWCASE_TARGET.y - layout.SHOWCASE_EXTENT.y / 2f - epsilon)
    }

    @Test
    fun `sideways the frames stand either side of the rocket`() {
        val strip = layout.showcase(strip = true)
        assertEquals(layout.SHOWCASE, layout.showcase(strip = false))
        assertEquals(layout.SHOWCASE.map { it.size }, strip.map { it.size })
        val frames = strip.take(2).map { layout.frameSize(it.size) }
        // Clear of the fins, the rocket's widest part.
        assertTrue(strip[0].position.x + frames[0].x / 2f < -layout.INSPECT_EXTENT.x / 2f)
        assertTrue(strip[1].position.x - frames[1].x / 2f > layout.INSPECT_EXTENT.x / 2f)
        val extent = layout.showcaseExtent(strip = true)
        val target = layout.showcaseTarget(strip = true)
        val right = strip.maxOf { it.position.x + layout.frameSize(it.size).x / 2f }
        assertTrue(right <= extent.x / 2f + epsilon)
        // The badge has no frame: its own size is its reach.
        val top = maxOf(
            strip.take(2).maxOf { it.position.y + layout.frameSize(it.size).y / 2f },
            strip[2].position.y + strip[2].size.y / 2f,
        )
        assertTrue(top <= target.y + extent.y / 2f + epsilon)
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
