package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.filament.Engine
import com.google.android.filament.LightManager
import io.github.sceneview.render.RenderTestHarness
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What Filament stores for a light's shadow options (google/filament#10565): the 1.77 bindings
 * zero `ShadowOptions()` and reorder the `transform` quaternion, which a JVM mock cannot see.
 */
@RunWith(AndroidJUnit4::class)
class LightNodeShadowOptionsTest {

    @Test
    fun builder_shadow_options_keep_native_defaults_and_identity_transform() = withEngine { engine ->
        val reference = LightNode(engine, LightManager.Type.DIRECTIONAL) { castShadows(true) }
        val node = LightNode(engine, LightManager.Type.DIRECTIONAL) {
            castShadows(true)
            shadowOptions(LightManager.ShadowOptions().apply { mapSize = 2048 })
        }
        try {
            val defaults = reference.storedOptions()
            val stored = node.storedOptions()
            assertEquals(2048, stored.mapSize)
            assertEquals(defaults.shadowCascades, stored.shadowCascades)
            assertTrue(stored.shadowCascades >= 1)
            assertEquals(defaults.normalBias, stored.normalBias, 0f)
            assertEquals(defaults.lispsm, stored.lispsm)
            assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), stored.getTransform(), 0f)
        } finally {
            node.destroy()
            reference.destroy()
        }
    }

    @Test
    fun builder_shadow_options_store_a_custom_transform_as_written() = withEngine { engine ->
        val quarterTurnAboutY = floatArrayOf(0f, 0.70710677f, 0f, 0.70710677f)
        val node = LightNode(engine, LightManager.Type.DIRECTIONAL) {
            castShadows(true)
            shadowOptions(LightManager.ShadowOptions().apply { setTransform(quarterTurnAboutY) })
        }
        try {
            assertArrayEquals(quarterTurnAboutY, node.storedOptions().getTransform(), 0f)
        } finally {
            node.destroy()
        }
    }

    @Test
    fun update_shadow_options_changes_only_the_fields_it_sets() = withEngine { engine ->
        val node = LightNode(engine, LightManager.Type.DIRECTIONAL) { castShadows(true) }
        try {
            val before = node.storedOptions()
            node.updateShadowOptions {
                mapSize = 512
                screenSpaceContactShadows = true
            }
            val after = node.storedOptions()
            assertEquals(512, after.mapSize)
            assertTrue(after.screenSpaceContactShadows)
            assertEquals(before.shadowCascades, after.shadowCascades)
            assertEquals(before.constantBias, after.constantBias, 0f)
            assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), after.getTransform(), 0f)
        } finally {
            node.destroy()
        }
    }

    private fun LightNode.storedOptions() = lightManager.getShadowOptions(lightInstance)

    private fun withEngine(block: (Engine) -> Unit) {
        val harness = RenderTestHarness(width = 64, height = 64)
        try {
            harness.runOnMain { block(harness.engine) }
        } finally {
            harness.destroy()
        }
    }
}
