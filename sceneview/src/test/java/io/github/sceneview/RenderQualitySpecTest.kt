package io.github.sceneview

import com.google.android.filament.View.QualityLevel
import com.google.android.filament.View.ShadowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what each [RenderQuality] preset writes to a Filament view, without an engine: the
 * presets are plain [RenderQualitySpec] values that `applyRenderQuality` copies onto the view.
 */
class RenderQualitySpecTest {

    @Test
    fun defaultIsThePremiumMiddleGround() {
        val spec = RenderQuality.Default.spec()
        assertTrue(spec.shadows)
        assertEquals(ShadowType.DPCF, spec.shadowType)
        assertTrue(spec.ssao)
        assertEquals(QualityLevel.MEDIUM, spec.ssaoQuality)
        assertEquals(QualityLevel.MEDIUM, spec.ssaoUpsampling)
        assertTrue(spec.bloom)
        assertEquals(0.1f, spec.bloomStrength, 0f)
        assertEquals(QualityLevel.MEDIUM, spec.hdrColorBuffer)
        // Kept cheap for an Adreno 6xx at 60 fps: no multisampled HDR target, fixed resolution.
        assertEquals(1, spec.msaaSampleCount)
        assertFalse(spec.dynamicResolution)
    }

    @Test
    fun cinematicSpendsMoreThanDefaultOnEveryAxis() {
        val spec = RenderQuality.Cinematic.spec()
        assertEquals(ShadowType.PCSS, spec.shadowType)
        assertEquals(QualityLevel.HIGH, spec.ssaoQuality)
        assertEquals(QualityLevel.HIGH, spec.ssaoUpsampling)
        assertEquals(QualityLevel.HIGH, spec.hdrColorBuffer)
        assertEquals(4, spec.msaaSampleCount)
        assertTrue(spec.bloomStrength > RenderQuality.Default.spec().bloomStrength)
    }

    @Test
    fun performanceDropsShadowsSsaoAndBloom() {
        val spec = RenderQuality.Performance.spec()
        assertFalse(spec.shadows)
        assertFalse(spec.ssao)
        assertFalse(spec.bloom)
        assertTrue(spec.dynamicResolution)
        assertEquals(QualityLevel.LOW, spec.hdrColorBuffer)
    }

    @Test
    fun anArViewNeverGetsSsaoOrBloomWhateverThePreset() {
        // Both would darken and tint the live camera background under the virtual content (#657).
        RenderQuality.entries.forEach { quality ->
            val spec = quality.spec(isArView = true)
            assertFalse("$quality turned SSAO on over the camera feed", spec.ssao)
            assertFalse("$quality turned bloom on over the camera feed", spec.bloom)
        }
    }

    @Test
    fun anArViewKeepsEverythingElseFromThePreset() {
        RenderQuality.entries.forEach { quality ->
            assertEquals(
                quality.spec().copy(ssao = false, bloom = false),
                quality.spec(isArView = true),
            )
        }
    }
}
