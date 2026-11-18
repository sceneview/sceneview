package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.materialOrNull
import io.github.sceneview.math.Size
import io.github.sceneview.render.RenderTestHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Filament bindings and material lifetimes cannot be exercised by a JVM-only mock. */
@RunWith(AndroidJUnit4::class)
class ContactShadowNodeTest {

    @Test
    fun updates_survive_geometry_changes_and_another_nodes_destruction() = withLoader { loader ->
        val first = ContactShadowNode(loader.engine, loader)
        val second = ContactShadowNode(loader.engine, loader)
        val firstInstance = first.materialInstance
        val secondInstance = second.materialInstance
        try {
            assertTrue(loader.tracks(firstInstance))
            assertTrue(loader.tracks(secondInstance))
            assertTrue(firstInstance.nativeObject != secondInstance.nativeObject)
            assertTrue(firstInstance.materialOrNull?.hasParameter("intensity") == true)

            repeat(6) { index ->
                first.context = ContactShadowContext.values()[index % 3]
                first.intensity = index / 5f
                first.updateGeometry(size = Size(1f + index, 0f, 1f + index))
                assertEquals(firstInstance.nativeObject, first.materialInstance.nativeObject)
                assertEquals(index / 5f, first.intensity, 0f)
            }

            first.destroy()
            assertFalse(loader.tracks(firstInstance))
            assertTrue(loader.tracks(secondInstance))
            second.context = ContactShadowContext.Wall
            second.intensity = 0.2f
            first.destroy() // #4259: must leave the surviving node and its instance alone.
            second.context = ContactShadowContext.TableTop
            second.intensity = 0.8f
            assertTrue(loader.tracks(secondInstance))
        } finally {
            first.destroy()
            second.destroy()
        }
        assertFalse(loader.tracks(secondInstance))
    }

    @Test
    fun updates_do_not_write_shadow_uniforms_to_a_replacement_material() = withLoader { loader ->
        val node = ContactShadowNode(loader.engine, loader)
        val shadowInstance = node.materialInstance
        val replacementMaterial = loader.createMaterial("materials/opaque_unlit_colored.filamat")
        val replacement = loader.createInstance(replacementMaterial)
        try {
            assertFalse(replacementMaterial.hasParameter("intensity"))
            node.materialInstance = replacement
            repeat(6) { index ->
                node.intensity = index / 5f
                node.context = ContactShadowContext.values()[index % 3]
                node.updateGeometry(size = Size(2f + index, 0f, 2f))
                assertEquals(replacement.nativeObject, node.materialInstance.nativeObject)
            }
            // Callers can restore the original binding after editing the shadow parameters.
            node.materialInstance = shadowInstance
            node.context = ContactShadowContext.Wall
            node.intensity = 0.4f
            node.materialInstance = replacement
            node.destroy()
            node.destroy()
            assertFalse(loader.tracks(shadowInstance))
            assertTrue("the caller owns the replacement", loader.tracks(replacement))
        } finally {
            node.destroy()
            loader.destroyMaterialInstance(replacement)
            loader.destroyMaterial(replacementMaterial)
        }
    }

    private fun withLoader(block: (MaterialLoader) -> Unit) {
        val harness = RenderTestHarness(width = 64, height = 64)
        try {
            harness.runOnMain {
                val loader = MaterialLoader(
                    harness.engine,
                    InstrumentationRegistry.getInstrumentation().targetContext
                )
                try {
                    block(loader)
                } finally {
                    loader.destroy()
                }
            }
        } finally {
            harness.destroy()
        }
    }
}
