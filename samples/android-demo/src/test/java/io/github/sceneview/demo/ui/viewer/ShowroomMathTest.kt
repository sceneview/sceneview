package io.github.sceneview.demo.ui.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class ShowroomMathTest {

    /** Filament 1.72's `fog()` (surface_fog.fs, exponential height fog), on the CPU. */
    private fun fogOpacity(fog: ShowroomMath.Fog, eye: DoubleArray, point: DoubleArray): Double {
        val view = DoubleArray(3) { point[it] - eye[it] }
        val d = sqrt(view[0] * view[0] + view[1] * view[1] + view[2] * view[2])
        if (d < fog.start) return 0.0
        val x = fog.density.toDouble()
        val y = -fog.falloff * (eye[1] - fog.height)
        val z = x * exp(y)
        val fh = fog.falloff * view[1]
        val pathPerMetre = if (abs(fh) > 0.00125) (z - x * exp(y - fh)) / fh else z
        return 1.0 - exp(-pathPerMetre * max(d - fog.start, 0.0))
    }

    /** A Peugeot 2008 framed like the viewer frames it: centre 0.8 m up, radius 2.45 m. */
    private val radius = 2.45f
    private val center = doubleArrayOf(0.0, 0.8, 0.0)
    private val floorY = 0f
    private val eyeDistance = 4.0 * radius
    private val pitch = ShowroomMath.pitchDegrees(radius) * PI / 180.0
    private val eye = doubleArrayOf(0.0, center[1] + eyeDistance * sin(pitch), eyeDistance * cos(pitch))
    private val fog = ShowroomMath.fog(eye[1].toFloat(), eyeDistance.toFloat(), floorY, radius)

    @Test
    fun `every floor point fades at the same rate and is gone 8 radii past the subject`() {
        listOf(0.0, 1.0, 3.0, 8.0, 20.0).forEach { behind ->
            // Floor points straight behind the subject, `behind` radii past where the fog starts.
            val target = fog.start + behind * radius
            val floorDistance = sqrt(target * target - (eye[1] - floorY) * (eye[1] - floorY))
            val point = doubleArrayOf(0.0, floorY.toDouble(), eye[2] - floorDistance)
            val expected = 1.0 - exp(-3.0 / (8.0 * radius) * (target - fog.start))
            assertEquals("floor $behind radii past the start", expected, fogOpacity(fog, eye, point), 1e-3)
        }
        val eightRadii = fog.start + 8.0 * radius
        val floorDistance = sqrt(eightRadii * eightRadii - eye[1] * eye[1])
        val opacity = fogOpacity(fog, eye, doubleArrayOf(floorDistance, 0.0, eye[2]))
        assertEquals(0.95, opacity, 2e-3)
    }

    @Test
    fun `the floor quad's edge is never seen`() {
        val edge = ShowroomMath.FLOOR_SIZE_RADII / 2.0 * radius
        val opacity = fogOpacity(fog, eye, doubleArrayOf(edge, 0.0, center[2] - edge))
        assertTrue("edge opacity $opacity", opacity > 0.9999)
    }

    @Test
    fun `the backdrop is a gradient from the horizon band to the zenith`() {
        fun sky(elevationDegrees: Double): Double {
            val theta = elevationDegrees * PI / 180.0
            val far = 1e9
            val point = doubleArrayOf(0.0, eye[1] + far * sin(theta), eye[2] - far * cos(theta))
            return fogOpacity(fog, eye, point)
        }
        assertEquals(0.5, sky(4.0), 2e-3)
        assertEquals(0.243, sky(10.0), 2e-3)
        assertTrue(sky(1.0) > 0.9)
        assertTrue(sky(30.0) < 0.1)
        // Below the horizon the fog is total: the floor and the backdrop meet in one colour.
        assertEquals(1.0, sky(-1.0), 1e-6)
    }

    @Test
    fun `the subject itself is never fogged`() {
        assertTrue(fog.start > eyeDistance + radius)
    }

    @Test
    fun `an eye at or under the floor and a degenerate subject still give a finite fog`() {
        listOf(
            ShowroomMath.fog(eyeY = -1f, eyeDistance = 3f, floorY = 0f, radius = 1f),
            ShowroomMath.fog(eyeY = 0f, eyeDistance = 3f, floorY = 0f, radius = 1f),
            ShowroomMath.fog(eyeY = 1f, eyeDistance = 3f, floorY = 0f, radius = 0f),
            ShowroomMath.fog(eyeY = 1f, eyeDistance = 3f, floorY = 0f, radius = Float.NaN),
        ).forEach {
            assertTrue("$it", it.start.isFinite() && it.density.isFinite())
            assertTrue("$it", it.height.isFinite() && it.falloff.isFinite() && it.falloff > 0f)
        }
    }

    @Test
    fun `a fog within a thousandth is not rewritten`() {
        val a = ShowroomMath.Fog(start = 10f, density = 0.2f, height = 0f, falloff = 0.5f)
        assertTrue(a.closeTo(a.copy(start = 10.005f)))
        assertTrue(a.closeTo(a.copy(height = 5e-7f)))
        assertFalse(a.closeTo(a.copy(height = 0.01f)))
        assertFalse(a.closeTo(a.copy(start = 10.1f)))
        assertFalse(a.closeTo(a.copy(falloff = 0.51f)))
        assertFalse(a.closeTo(null))
    }

    @Test
    fun `a car is shot low and a watch from a tabletop eye`() {
        assertEquals(22f, ShowroomMath.pitchDegrees(0.05f), 0f)
        assertEquals(22f, ShowroomMath.pitchDegrees(0.2f), 0f)
        assertEquals(14f, ShowroomMath.pitchDegrees(0.6f), 0f)
        assertEquals(14f, ShowroomMath.pitchDegrees(1.3f), 0f)
        assertEquals(9f, ShowroomMath.pitchDegrees(3f), 0f)
        assertEquals(9f, ShowroomMath.pitchDegrees(9f), 0f)
        assertEquals(22f, ShowroomMath.pitchDegrees(Float.NaN), 0f)
        assertEquals(22f, ShowroomMath.pitchDegrees(0f), 0f)
        assertEquals(22f, ShowroomMath.pitchDegrees(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun `the showroom follows the theme and is recognised by its path`() {
        assertEquals(ShowroomMath.DARK_HDR, ShowroomMath.hdrPath(isDark = true))
        assertEquals(ShowroomMath.LIGHT_HDR, ShowroomMath.hdrPath(isDark = false))
        assertTrue(ShowroomMath.isShowroom(ShowroomMath.DARK_HDR))
        assertTrue(ShowroomMath.isShowroom(ShowroomMath.LIGHT_HDR))
        assertFalse(ShowroomMath.isShowroom("environments/studio_warm_2k.hdr"))
        assertFalse(ShowroomMath.isShowroom(null))
        assertEquals(ShowroomMath.DARK_SHOWROOM_INTENSITY, ShowroomMath.baseIntensity(ShowroomMath.DARK_HDR), 0f)
        assertEquals(ShowroomMath.SHOWROOM_INTENSITY, ShowroomMath.baseIntensity(ShowroomMath.LIGHT_HDR), 0f)
        assertEquals(ShowroomMath.LEGACY_INTENSITY, ShowroomMath.baseIntensity("environments/studio_2k.hdr"), 0f)
        assertEquals(ShowroomMath.LEGACY_INTENSITY, ShowroomMath.baseIntensity(null), 0f)
    }

    @Test
    fun `upward irradiance is looked up by the map's stem`() {
        assertEquals(1.5669f, ShowroomMath.upwardIrradiance(ShowroomMath.DARK_HDR).x, 0f)
        assertEquals(0.6459f, ShowroomMath.upwardIrradiance("environments/chinese_garden_2k.hdr").z, 0f)
        assertEquals(0.5391f, ShowroomMath.upwardIrradiance("environments/studio_2k.hdr").z, 0f)
        assertEquals(0.6f, ShowroomMath.upwardIrradiance("environments/unknown_2k.hdr").y, 0f)
        assertEquals(0.6f, ShowroomMath.upwardIrradiance(null).x, 0f)
    }

    @Test
    fun `the SDK camera's exposure`() {
        assertEquals(1.0 / 17280.0, ShowroomMath.exposure(12f, 1f / 200f, 200f).toDouble(), 1e-9)
    }

    @Test
    fun `the charcoal floor reaches the screen as its token`() {
        val exposure = ShowroomMath.exposure(12f, 1f / 200f, 200f)
        listOf(
            SceneViewTokens.Showroom.Dark.pool to ShowroomMath.DARK_HDR,
            SceneViewTokens.Showroom.Dark.floor to ShowroomMath.DARK_HDR,
        ).forEach { (token, hdr) ->
            val irradiance = ShowroomMath.upwardIrradiance(hdr)
            val intensity = ShowroomMath.baseIntensity(hdr)
            val albedo = ShowroomMath.floorAlbedo(token, true, irradiance, intensity, exposure, lights = false)
            val lit = floatArrayOf(
                albedo.x * irradiance.x * intensity * exposure,
                albedo.y * irradiance.y * intensity * exposure,
                albedo.z * irradiance.z * intensity * exposure,
            )
            val shown = ViewerBackdrop.pbrNeutral(lit)
            val expected = token.convert(ColorSpaces.LinearSrgb)
            assertEquals("$token red", expected.red, shown[0], 1e-4f)
            assertEquals("$token green", expected.green, shown[1], 1e-4f)
            assertEquals("$token blue", expected.blue, shown[2], 1e-4f)
        }
    }

    @Test
    fun `a floor brighter than the light allows is clamped, a broken exposure is not NaN`() {
        val dim = Float3(0.01f)
        val white = ShowroomMath.floorAlbedo(Color.White, true, dim, 18_000f, 5.787e-5f, lights = false)
        assertEquals(0.9f, white.x, 0f)
        val broken = ShowroomMath.floorAlbedo(Color.Gray, true, Float3(1f), 18_000f, Float.NaN, lights = false)
        assertEquals(0.02f, broken.y, 0f)
        val dark = ShowroomMath.floorAlbedo(Color.Black, false, Float3(1f), 30_000f, 5.787e-5f, lights = true)
        assertEquals(0.02f, dark.z, 0f)
    }

    @Test
    fun `the white studio's floor is white paint, never brighter than a real floor`() {
        val exposure = ShowroomMath.exposure(12f, 1f / 200f, 200f)
        val hdr = ShowroomMath.LIGHT_HDR
        listOf(SceneViewTokens.Showroom.Light.pool, SceneViewTokens.Showroom.Light.floor).forEach { token ->
            val irradiance = ShowroomMath.upwardIrradiance(hdr)
            val intensity = ShowroomMath.baseIntensity(hdr)
            val albedo = ShowroomMath.floorAlbedo(token, true, irradiance, intensity, exposure, lights = false)
            assertTrue("$token", albedo.x in 0.5f..0.9f && albedo.y in 0.5f..0.9f && albedo.z in 0.5f..0.9f)
        }
    }

    @Test
    fun `the lights share the floor's light with the IBL`() {
        val exposure = 5.787e-5f
        val token = SceneViewTokens.Showroom.Dark.floor
        val unlit = ShowroomMath.floorAlbedo(token, false, Float3(0.6f), 30_000f, exposure, lights = false)
        val lit = ShowroomMath.floorAlbedo(token, false, Float3(0.6f), 30_000f, exposure, lights = true)
        assertTrue(lit.x < unlit.x)
    }

    @Test
    fun `the horizon fog colour comes out of the IBL luminance as the token`() {
        val exposure = 5.787e-5f
        val token = SceneViewTokens.Showroom.Dark.horizon
        val color = ShowroomMath.fogColor(token, neutral = true, baseIntensity = 18_000f, exposure = exposure)
        val before = ShowroomMath.linearBefore(token, neutral = true)
        color.forEachIndexed { i, c -> assertEquals(before[i], c * 18_000f * exposure, 1e-5f) }
        assertTrue(ShowroomMath.fogColor(token, true, 18_000f, 0f).all { it == 0f })
    }

    @Test
    fun `ambient occlusion scales with the subject, within bounds`() {
        assertEquals(0.294f, ShowroomMath.aoRadius(2.45f), 1e-4f)
        assertEquals(0.005f, ShowroomMath.aoRadius(0.01f), 0f)
        assertEquals(0.005f, ShowroomMath.aoRadius(Float.NaN), 0f)
        assertEquals(0.5f, ShowroomMath.aoRadius(100f), 0f)
        assertEquals(0.05f, ShowroomMath.aoBilateralThreshold(10f), 0f)
    }

    @Test
    fun `the reflection is fainter on the white studio and fades up the lower 40 percent`() {
        assertEquals(0.26f, ShowroomMath.reflectionStrength(isDark = true), 0f)
        assertEquals(0.14f, ShowroomMath.reflectionStrength(isDark = false), 0f)
        assertEquals(0.6f, ShowroomMath.reflectionFadeHeight(1.5f), 1e-6f)
        assertTrue(ShowroomMath.reflectionFadeHeight(0f) > 0f)
        assertTrue(ShowroomMath.reflectionFadeHeight(Float.NaN) > 0f)
    }

    @Test
    fun `the mirror eye is the eye reflected through the floor plane`() {
        // An eye 3 m up at z = 10, looking down -Z, over a floor at y = 0.5.
        val eye = DoubleArray(16).also {
            it[0] = 1.0; it[5] = 1.0; it[10] = 1.0; it[15] = 1.0
            it[12] = 0.0; it[13] = 3.0; it[14] = 10.0
        }
        val out = ShowroomMath.mirrorModelMatrix(eye, floorY = 0.5f, out = DoubleArray(16))
        assertEquals(-2.0, out[13], 1e-9) // 0.5 - (3 - 0.5)
        assertEquals(10.0, out[14], 1e-9)
        assertEquals(-1.0, out[5], 1e-9) // the up axis flips
        assertEquals(1.0, out[0], 1e-9)
        assertEquals(1.0, out[10], 1e-9)
        assertEquals(1.0, out[15], 1e-9)
    }

    @Test
    fun `the floor keeps its sheen at every framing and goes matte seen from above`() {
        // Elevation e at distance 1: eye height sin(e) above the centre.
        fun at(deg: Double) = ShowroomMath.sheen(sin(deg * PI / 180).toFloat(), 0f, 1f)
        assertEquals(1f, at(22.0), 1e-6f)
        assertEquals(1f, at(ShowroomMath.SHEEN_FULL_DEGREES.toDouble()), 1e-4f)
        assertEquals(0f, at(ShowroomMath.SHEEN_GONE_DEGREES.toDouble()), 1e-4f)
        assertEquals(0f, at(89.0), 1e-6f)
        val mid = at(47.5)
        assertTrue(mid > 0.2f && mid < 0.8f)
        assertTrue(at(40.0) > at(50.0))
    }

    @Test
    fun `a degenerate eye keeps the sheen`() {
        assertEquals(1f, ShowroomMath.sheen(1f, 0f, 0f), 0f)
        assertEquals(1f, ShowroomMath.sheen(1f, 0f, Float.NaN), 0f)
    }
}
