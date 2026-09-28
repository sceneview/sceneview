package io.github.sceneview.demo.demos.internal

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Materials demo's soft backdrop (#4065): the CPU half that turns a Radiance HDR into
 * the six faces of a skybox cube. Pure JVM — the Filament upload is checked by capture.
 */
class StudioBackdropTest {

    @Test
    fun `decodes a flat RGBE file`() {
        // Two pixels: (128, 64, 32) at exponent 129 -> 2^(129-136) = 1/128 a step.
        val header = "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 2\n".toByteArray()
        val pixels = byteArrayOf(128.toByte(), 64, 32, 129.toByte(), 0, 0, 0, 0)
        val image = StudioBackdrop.decodeRgbe(header + pixels)
        assertEquals(2, image.width)
        assertEquals(1, image.height)
        assertEquals(1f, image.rgb[0], 1e-6f)
        assertEquals(0.5f, image.rgb[1], 1e-6f)
        assertEquals(0.25f, image.rgb[2], 1e-6f)
        assertEquals(0f, image.rgb[3], 0f)
    }

    @Test
    fun `decodes a run-length encoded scanline`() {
        val width = 8
        val out = ByteArrayOutputStream()
        out.write("#?RADIANCE\n\n-Y 1 +X $width\n".toByteArray())
        out.write(byteArrayOf(2, 2, 0, width.toByte()))
        // R: a run of 8 x 128. G: 8 literal values. B: a run of 8 x 0. E: a run of 8 x 129.
        out.write(byteArrayOf((128 + 8).toByte(), 128.toByte()))
        out.write(byteArrayOf(8, 0, 16, 32, 48, 64, 80, 96, 112))
        out.write(byteArrayOf((128 + 8).toByte(), 0))
        out.write(byteArrayOf((128 + 8).toByte(), 129.toByte()))
        val image = StudioBackdrop.decodeRgbe(out.toByteArray())
        for (x in 0 until width) {
            assertEquals(1f, image.rgb[x * 3], 1e-6f)
            assertEquals(x * 16 / 128f, image.rgb[x * 3 + 1], 1e-6f)
            assertEquals(0f, image.rgb[x * 3 + 2], 0f)
        }
    }

    @Test
    fun `every bundled studio environment decodes to finite light`() {
        MaterialStudio.environments.forEach { environment ->
            val image = StudioBackdrop.decodeRgbe(asset(environment.assetPath).readBytes())
            assertEquals("${environment.label} is not 2:1", image.width, image.height * 2)
            assertTrue(image.rgb.all { it.isFinite() && it >= 0f })
            assertTrue("${environment.label} decoded black", image.rgb.any { it > 0.1f })
        }
    }

    @Test
    fun `the highlight roll-off leaves the midtones alone and never reaches the ceiling`() {
        val knee = StudioBackdrop.KNEE
        val ceiling = StudioBackdrop.CEILING
        assertEquals(0.5f, StudioBackdrop.rollOff(0.5f), 0f)
        assertEquals(knee, StudioBackdrop.rollOff(knee), 0f)
        // Continuous in slope at the knee: no visible crease where compression starts.
        val slope = (StudioBackdrop.rollOff(knee + 1e-3f) - knee) / 1e-3f
        assertEquals(1f, slope, 1e-2f)
        var previous = knee
        for (value in listOf(1.5f, 3f, 10f, 100f, 10_000f)) {
            val rolled = StudioBackdrop.rollOff(value)
            assertTrue("roll-off is not monotonic at $value", rolled > previous)
            assertTrue("$value rolled past the ceiling", rolled < ceiling)
            previous = rolled
        }
    }

    @Test
    fun `compressing highlights keeps their hue`() {
        val image = EquirectImage(1, 1, floatArrayOf(40f, 20f, 10f))
        StudioBackdrop.compressHighlights(image)
        assertEquals(2f, image.rgb[0] / image.rgb[1], 1e-4f)
        assertEquals(2f, image.rgb[1] / image.rgb[2], 1e-4f)
        assertTrue(image.rgb[0] < 40f)
    }

    @Test
    fun `the blur conserves light and wraps across the panorama seam`() {
        val width = 16
        val height = 8
        val rgb = FloatArray(width * height * 3)
        // A lit column on the seam's left edge.
        for (y in 0 until height) rgb[(y * width) * 3] = 1f
        val blurred = StudioBackdrop.blur(EquirectImage(width, height, rgb), sigma = 1.5f)
        val before = rgb.sum()
        val after = blurred.rgb.sum()
        assertEquals(before, after, 1e-3f)
        // Light crossed the seam: the last column received some of it.
        assertTrue(blurred.rgb[(width - 1) * 3] > 0.05f)
    }

    @Test
    fun `face centres look down the six axes`() {
        val size = 4
        val dir = FloatArray(3)
        val expected = listOf(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
            floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f),
        )
        expected.forEachIndexed { face, axis ->
            StudioBackdrop.direction(face, size / 2f, size / 2f, size, dir)
            for (c in 0 until 3) assertEquals("face $face", axis[c], dir[c], 1e-6f)
        }
    }

    @Test
    fun `the top of each side face looks up`() {
        val dir = FloatArray(3)
        for (face in listOf(0, 1, 4, 5)) {
            StudioBackdrop.direction(face, 2f, 0f, 4, dir)
            assertTrue("face $face row 0 is not the top", dir[1] > 0.5f)
        }
    }

    @Test
    fun `up samples the panorama's top row and forward its centre`() {
        val width = 8
        val height = 4
        val rgb = FloatArray(width * height * 3) { i -> (i / 3 / width).toFloat() } // value = row
        val image = EquirectImage(width, height, rgb)
        val out = FloatArray(3)
        StudioBackdrop.sample(image, 0f, 1f, 0f, out)
        assertEquals(0f, out[0], 1e-5f)
        StudioBackdrop.sample(image, 0f, -1f, 0f, out)
        assertEquals((height - 1).toFloat(), out[0], 1e-5f)
        StudioBackdrop.sample(image, 0f, 0f, 1f, out)
        assertEquals((height - 1) / 2f, out[0], 1e-5f)
    }

    @Test
    fun `the cube is mirrored in x like Filament's own equirect-to-cube pass`() {
        // equirectToCube.mat with Config.mirror = true fills the +X face from direction -X,
        // which is u = 0.25 on the panorama. Light only that column and look at the faces.
        val width = 16
        val height = 8
        val rgb = FloatArray(width * height * 3)
        for (y in 0 until height) for (x in 3..4) rgb[(y * width + x) * 3] = 1f
        val size = 8
        val faces = StudioBackdrop.buildCubeFaces(EquirectImage(width, height, rgb), size)
        fun centre(face: Int): Float = faces[(face * size * size + (size / 2) * size + size / 2) * 3]
        assertTrue("+X face centre is dark: ${centre(0)}", centre(0) > 0.5f)
        assertEquals("-X face centre should be dark", 0f, centre(1), 1e-6f)
    }

    @Test
    fun `a uniform panorama builds a uniform cube`() {
        val image = EquirectImage(16, 8, FloatArray(16 * 8 * 3) { 0.75f })
        val faces = StudioBackdrop.buildCubeFaces(image, size = 8)
        assertEquals(6 * 8 * 8 * 3, faces.size)
        assertTrue(faces.all { abs(it - 0.75f) < 1e-5f })
    }

    private fun asset(assetPath: String): File {
        val relative = "samples/android-demo/src/main/assets/$assetPath"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $relative from ${File("").absolutePath}")
    }
}
