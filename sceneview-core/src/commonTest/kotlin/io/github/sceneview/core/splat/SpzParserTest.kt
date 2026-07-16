package io.github.sceneview.core.splat

import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpzParserTest {

    private val samples = listOf(
        SplatSample(
            position = floatArrayOf(1.0f, -2.5f, 0.25f),
            logScale = floatArrayOf(-2.0f, -3.0f, -1.5f),
            quaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f), // identity
            color = floatArrayOf(0.5f, 0.55f, 0.6f),
            opacity = 0.8f,
        ),
        SplatSample(
            position = floatArrayOf(-0.5f, 4.0f, -1.25f),
            logScale = floatArrayOf(-4.0f, -4.0f, -4.0f),
            quaternionXyzw = quaternionAboutZ(0.5236f), // 30° about +Z, w largest & positive
            color = floatArrayOf(0.2f, 0.7f, 0.9f),
            opacity = 0.3f,
        ),
        SplatSample(
            position = floatArrayOf(0.0f, 0.0f, 3.0f),
            logScale = floatArrayOf(-1.0f, -2.0f, -3.0f),
            quaternionXyzw = floatArrayOf(0.17365f, 0f, 0f, 0.98481f), // 20° about +X
            color = floatArrayOf(0.95f, 0.05f, 0.5f),
            opacity = 0.99f,
        ),
    )

    private fun assertDecodes(cloud: SplatCloud) {
        assertEquals(samples.size, cloud.count)
        for (i in samples.indices) {
            val s = samples[i]
            // positions: 24-bit fixed point at 12 fractional bits (~0.25 mm).
            assertEquals(s.position[0], cloud.positions[i * 3], POS_TOL, "pos.x[$i]")
            assertEquals(s.position[1], cloud.positions[i * 3 + 1], POS_TOL, "pos.y[$i]")
            assertEquals(s.position[2], cloud.positions[i * 3 + 2], POS_TOL, "pos.z[$i]")
            // scales: linear = exp(logScale).
            assertEquals(exp(s.logScale[0]), cloud.scales[i * 3], SCALE_TOL, "scale.x[$i]")
            assertEquals(exp(s.logScale[1]), cloud.scales[i * 3 + 1], SCALE_TOL, "scale.y[$i]")
            assertEquals(exp(s.logScale[2]), cloud.scales[i * 3 + 2], SCALE_TOL, "scale.z[$i]")
            // colors: linear rgb 0..1.
            assertEquals(s.color[0], cloud.colors[i * 3], COLOR_TOL, "color.r[$i]")
            assertEquals(s.color[1], cloud.colors[i * 3 + 1], COLOR_TOL, "color.g[$i]")
            assertEquals(s.color[2], cloud.colors[i * 3 + 2], COLOR_TOL, "color.b[$i]")
            // opacity: 0..1.
            assertEquals(s.opacity, cloud.opacities[i], OPACITY_TOL, "opacity[$i]")
            // rotation: normalized xyzw, w reconstructed.
            for (c in 0 until 4) {
                assertEquals(s.quaternionXyzw[c], cloud.rotations[i * 4 + c], QUAT_TOL, "quat[$i][$c]")
            }
            assertEquals(1f, quatLength(cloud.rotations, i * 4), 1e-4f, "quat normalized [$i]")
        }
    }

    @Test
    fun parsesVersion2FirstThreeQuaternions() {
        assertDecodes(SplatParser.fromSpz(buildSpzGzip(samples, version = 2)))
    }

    @Test
    fun parsesVersion3SmallestThreeQuaternions() {
        assertDecodes(SplatParser.fromSpz(buildSpzGzip(samples, version = 3)))
    }

    @Test
    fun rejectsUnsupportedVersion1() {
        val e = assertFailsWith<SplatParseException> {
            SplatParser.fromSpz(buildSpzGzip(samples, version = 1))
        }
        assertTrue(e.message?.contains("version 1") == true, e.message)
    }

    @Test
    fun rejectsUncompressedNgspV4() {
        // Uncompressed NGSP magic → clear "v4 not supported" error, not a crash.
        val ngsp = bytesOf('N'.code, 'G'.code, 'S'.code, 'P'.code, 4, 0, 0, 0, 1, 0, 0, 0)
        assertFailsWith<SplatParseException> { SplatParser.fromSpz(ngsp) }
    }

    @Test
    fun rejectsTruncatedSpzBody() {
        val full = buildSpzGzip(samples, version = 2)
        // Re-gzip a truncated uncompressed payload so the header claims more splats than present.
        val raw = Inflate.gunzip(full)
        val truncated = gzipStored(raw.copyOf(raw.size - 5))
        assertFailsWith<SplatParseException> { SplatParser.fromSpz(truncated) }
    }

    @Test
    fun rejectsNonSpzInput() {
        assertFailsWith<SplatParseException> { SplatParser.fromSpz(bytesOf(1, 2, 3, 4, 5, 6)) }
    }

    @Test
    fun parsesLargeCloudWithoutQuadraticBlowup() {
        val count = 100_000
        val big = ArrayList<SplatSample>(count)
        for (i in 0 until count) {
            val a = i.toFloat()
            big.add(
                SplatSample(
                    position = floatArrayOf(a * 0.001f, -a * 0.002f, (i % 7).toFloat()),
                    logScale = floatArrayOf(-3f, -3f, -3f),
                    quaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f),
                    color = floatArrayOf(0.5f, 0.5f, 0.5f),
                    opacity = 0.5f,
                )
            )
        }
        val cloud = SplatParser.fromSpz(buildSpzGzip(big, version = 2))
        assertEquals(count, cloud.count)
        // Spot-check first and last splats decoded into the right slots.
        assertEquals(0f, cloud.positions[0], POS_TOL)
        assertEquals((count - 1) * 0.001f, cloud.positions[(count - 1) * 3], 0.05f)
    }

    private companion object {
        const val POS_TOL = 1e-3f
        const val SCALE_TOL = 1e-3f
        const val COLOR_TOL = 0.02f
        const val OPACITY_TOL = 0.01f
        const val QUAT_TOL = 0.02f
    }
}
