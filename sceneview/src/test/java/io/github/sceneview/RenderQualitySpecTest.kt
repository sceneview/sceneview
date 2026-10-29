package io.github.sceneview

import com.google.android.filament.View.QualityLevel
import com.google.android.filament.View.ShadowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertTrue(spec.ssao)
        assertTrue(spec.bloom)
        assertEquals(0.1f, spec.bloomStrength, 0f)
        assertEquals(QualityLevel.MEDIUM, spec.hdrColorBuffer)
        // Kept cheap for an Adreno 6xx at 60 fps: no multisampled HDR target, fixed resolution.
        assertEquals(1, spec.msaaSampleCount)
        assertFalse(spec.dynamicResolution)
    }

    @Test
    fun defaultLeavesTheShadowFilterAndSsaoQualityToTheView() {
        // Re-applying Default (SceneView does it on every renderQuality change) must not wipe a
        // caller's `setShadowType` or SSAO quality: those are creation defaults, not preset values.
        RenderQuality.Default.spec().let { spec ->
            assertNull(spec.shadowType)
            assertNull(spec.ssaoQuality)
            assertNull(spec.ssaoUpsampling)
        }
        assertEquals(ShadowType.DPCF, CREATION_SHADOW_TYPE)
        assertEquals(QualityLevel.MEDIUM, CREATION_SSAO_QUALITY)
    }

    @Test
    fun onlyCinematicAndPerformanceWriteTheShadowFilterAndSsaoQuality() {
        listOf(RenderQuality.Cinematic, RenderQuality.Performance).forEach { quality ->
            val spec = quality.spec()
            assertNotNull("$quality shadow type", spec.shadowType)
            assertNotNull("$quality SSAO quality", spec.ssaoQuality)
            assertNotNull("$quality SSAO upsampling", spec.ssaoUpsampling)
        }
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
