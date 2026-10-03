package io.github.sceneview.demo.demos

import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.length
import io.github.sceneview.demo.BandLens
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.math.Position
import io.github.sceneview.node.ContactShadowContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Contact Shadow Preview's home shot (#4326): the authored one upright, and on a phone held
 * sideways one that holds the boxes, their labels, the pool and the TV in the band above the
 * controls without stretching the room across a 140° field.
 */
class ContactShadowHomeShotTest {

    // The band above the controls on a Pixel 7a held sideways: 866 x 119 dp.
    private val bandAspect = 866f / 119f

    private val upright = contactShadowHomeShot(strip = false)
    private val strip = contactShadowHomeShot(strip = true)

    private fun lens(shot: ContactShadowShot) =
        BandLens(shot.eye, shot.target, shot.focalLengthMm, bandAspect)

    private val boxFront = BOXES_Z + BOX_EDGE_METERS / 2f
    private val boxOuter = BOX_HALF_SPACING + BOX_EDGE_METERS / 2f

    /** Top of the higher of the two labels, at the top of the hop and of the hover. */
    private val labelTop = maxOf(
        BOX_EDGE_METERS + DemoMath.CONTACT_BOUNCE_MAX_HEIGHT_METERS,
        DemoMath.CONTACT_FLOAT_CENTER_Y_METERS + DemoMath.CONTACT_FLOAT_BOB_METERS + BOX_EDGE_METERS / 2f,
    ) + CONTACT_LABEL_GAP_METERS + CONTACT_LABEL_HEIGHT_METERS / 2f

    private val tvTop = TV_CENTER_Y_METERS + TV_HEIGHT_METERS / 2f
    private val tvBottom = TV_CENTER_Y_METERS - TV_HEIGHT_METERS / 2f

    @Test
    fun `upright the shot is the authored one`() {
        assertEquals(CONTACT_CAMERA_EYE, upright.eye)
        assertEquals(CONTACT_CAMERA_TARGET, upright.target)
        assertEquals(AUTHORED_FOCAL_LENGTH_MM, upright.focalLengthMm, 0.0)
    }

    @Test
    fun `sideways the shot stands further back on the same line of sight, behind a longer lens`() {
        assertEquals(CONTACT_CAMERA_TARGET, strip.target)
        val authored = CONTACT_CAMERA_EYE - CONTACT_CAMERA_TARGET
        val pulledBack = strip.eye - strip.target
        assertEquals(0f, length(cross(authored, pulledBack)), 0.0001f)
        assertTrue(length(pulledBack) > length(authored))
        assertTrue(strip.focalLengthMm > upright.focalLengthMm)
    }

    @Test
    fun `sideways the boxes, their pool, the labels and the TV are drawn inside the band`() {
        val lens = lens(strip)
        // The grounded box's pool at contact: the fully dark ellipse of the Floor preset.
        val poolFront = BOXES_Z + ContactShadowContext.Floor.radiusV * SHADOW_QUAD_METERS
        val subject = listOf(-1f, 1f).flatMap { side ->
            listOf(
                Position(side * boxOuter, 0f, boxFront),
                Position(side * BOX_HALF_SPACING, 0f, poolFront),
                Position(side * boxOuter, labelTop, BOXES_Z),
                Position(side * TV_WIDTH_METERS / 2f, tvTop, WALL_Z),
                Position(side * TV_WIDTH_METERS / 2f, tvBottom, WALL_Z),
            )
        }
        subject.forEach { point ->
            assertTrue("$point is at ${lens.project(point)}", lens.holds(point))
        }
        // With air above the TV: its top edge is not on the band's.
        assertTrue(lens.project(Position(0f, tvTop, WALL_Z)).second < 0.9)
    }

    @Test
    fun `from the authored eye the lens that fills the band with the TV loses the boxes`() {
        // Why the strip shot steps back instead of only zooming in: 55 mm from the authored eye
        // draws the TV larger, and the boxes below the band, behind the controls.
        val zoomed = BandLens(CONTACT_CAMERA_EYE, CONTACT_CAMERA_TARGET, focalLengthMm = 55.0, aspect = bandAspect)
        assertTrue(zoomed.project(Position(0f, 0f, boxFront)).second < -1.0)
    }

    @Test
    fun `sideways the labels stay under the shadow the TV casts below itself`() {
        val lens = lens(strip)
        // Lower edge of the Wall preset's dark ellipse, on its quad centred on the TV.
        val wall = ContactShadowContext.Wall
        val shadowBottom = TV_CENTER_Y_METERS + (wall.centerV - wall.radiusV) * TV_SHADOW_HEIGHT_METERS
        assertTrue("the shadow shows under the TV", shadowBottom < tvBottom)
        val shadowOnScreen = lens.project(Position(0f, shadowBottom, WALL_Z)).second
        val labelOnScreen = lens.project(Position(0f, labelTop, BOXES_Z)).second
        assertTrue("label top at $labelOnScreen, shadow down to $shadowOnScreen", labelOnScreen < shadowOnScreen)
    }

    @Test
    fun `sideways the TV is drawn half again as large as the authored lens draws it in the band`() {
        val left = Position(-TV_WIDTH_METERS / 2f, TV_CENTER_Y_METERS, WALL_Z)
        val right = Position(TV_WIDTH_METERS / 2f, TV_CENTER_Y_METERS, WALL_Z)
        val authored = lens(upright).widthInHeights(left, right)
        val pulledBack = lens(strip).widthInHeights(left, right)
        assertTrue("TV ${pulledBack / authored} times as wide", pulledBack >= authored * 1.5)
    }

    @Test
    fun `sideways the room is seen through a field near 100 degrees instead of 140`() {
        assertTrue(lens(upright).horizontalFieldDegrees > 135.0)
        assertTrue(lens(strip).horizontalFieldDegrees < 105.0)
    }
}
