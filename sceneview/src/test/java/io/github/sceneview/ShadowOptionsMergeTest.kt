package io.github.sceneview

import com.google.android.filament.LightManager.ShadowOptions
import io.github.sceneview.components.copy
import io.github.sceneview.components.mergeWithNativeDefaults
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The merge behind `LightNode(type) { shadowOptions(…) }` on Filament 1.77 (google/filament#10565). */
class ShadowOptionsMergeTest {

    private fun nativeDefaults() = ShadowOptions().apply {
        mapSize = 1024
        shadowCascades = 1
        setCascadeSplitPositions(0.125f, 0.25f, 0.5f)
        constantBias = 0.001f
        normalBias = 1f
        shadowNearHint = 1f
        shadowFarHint = 100f
        lispsm = true
        polygonOffsetConstant = 0.5f
        polygonOffsetSlope = 2f
        stepCount = 8
        maxShadowDistance = 0.3f
        shadowBulbRadius = 0.02f
        setTransform(0f, 0f, 0f, 1f)
        penumbraScale = 1f
        penumbraRatioScale = 1f
    }

    @Test
    fun untouched_fields_take_the_native_defaults() {
        val requested = ShadowOptions().apply {
            mapSize = 2048
            screenSpaceContactShadows = true
        }
        val merged = mergeWithNativeDefaults(requested, nativeDefaults())
        assertEquals(2048, merged.mapSize)
        assertTrue(merged.screenSpaceContactShadows)
        assertEquals(1, merged.shadowCascades)
        assertEquals(1f, merged.normalBias, 0f)
        assertTrue(merged.lispsm)
        assertEquals(8, merged.stepCount)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), merged.getTransform(), 0f)
        assertArrayEquals(floatArrayOf(0.125f, 0.25f, 0.5f), merged.cascadeSplitPositions, 0f)
    }

    @Test
    fun a_written_transform_is_kept() {
        val turn = floatArrayOf(0f, 0.70710677f, 0f, 0.70710677f)
        val merged = mergeWithNativeDefaults(ShadowOptions().apply { setTransform(turn) }, nativeDefaults())
        assertArrayEquals(turn, merged.getTransform(), 0f)
    }

    @Test
    fun copy_is_independent_of_its_source() {
        val source = nativeDefaults()
        val copy = source.copy()
        source.mapSize = 64
        source.lispsm = false
        assertEquals(1024, copy.mapSize)
        assertTrue(copy.lispsm)
        assertFalse(source.lispsm)
    }
}
