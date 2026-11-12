package io.github.sceneview.demo.demos.internal.feel

import io.github.sceneview.demo.ui.viewer.ViewerBackdrop
import kotlin.math.pow
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatterIdentityTest {
    @Test fun coloursMatchBothIdentityAssets() {
        assertEquals(listOf(0xC9D9FF, 0x005BC1, 0x004494), MatterIdentity.colours(false))
        assertEquals(listOf(0xC9D9FF, 0x005BC1, 0xADC6FF), MatterIdentity.colours(true))
    }

    @Test fun paletteSurvivesFilamentRec2020GradingWithoutNegativeHdrChannels() {
        for (rgb in (MatterIdentity.colours(false) + MatterIdentity.colours(true)).distinct()) {
            val hdr = MatterIdentity.linearBeforeToneMap(rgb)
            assertTrue(hdr.all { it.isFinite() && it >= 0f })
            val display = MatterIdentity.toSrgb(ViewerBackdrop.pbrNeutral(MatterIdentity.toRec2020(hdr)))
            display.forEachIndexed { channel, value ->
                val srgb = if (value <= .0031308f) value * 12.92f else 1.055f * value.pow(1f / 2.4f) - .055f
                assertEquals(((rgb shr ((2 - channel) * 8)) and 255) / 255f, srgb, .001f)
            }
        }
    }

    @Test fun frontEdgeBisectsTheCameraAndFacesProjectToTheCorrectSides() {
        for (azimuth in listOf(-180f, 0f, MatterStage.AZIMUTH, 90f, 180f)) {
            val relative = Math.toRadians((azimuth - MatterIdentity.yaw(azimuth)).toDouble())
            // Camera coordinates in the cube's local horizontal plane.
            val x = sin(relative)
            val z = cos(relative)
            assertTrue(x > 0 && z > 0)
            assertEquals(x, z, .000001)
            // Camera right = (cos azimuth, 0, -sin azimuth).
            assertTrue(-sin(relative) < 0) // +Z is the left blue face.
            assertTrue(cos(relative) > 0) // +X is the right primary face.
        }
    }
}
