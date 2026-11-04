package io.github.sceneview.environment

import android.opengl.EGL14
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.EntityManager
import io.github.sceneview.createEglContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SceneView's Filament init reserves the first entity slots before any other entity exists
 * ([PrefilterEntitySlots]).
 *
 * The pixel-level proof is `HdrEnvironmentSecondEngineRenderTest`, which needs GPU readback. This
 * one needs none, so it runs on every emulator, SwiftShader included: it guards the precondition the
 * workaround depends on, that the reservation runs first on SceneView's real init path
 * ([createEglContext]) and gets slot 1.
 */
@RunWith(AndroidJUnit4::class)
class PrefilterEntitySlotsTest {

    private companion object {
        /** Low bits of an entity id: its slot index (`Entity::GENERATION_SHIFT` = 17). */
        const val INDEX_MASK = (1 shl 17) - 1
    }

    @Before
    fun initFilamentTheWayScenesDo() = onMain {
        val context = createEglContext()
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglMakeCurrent(
            display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
        EGL14.eglDestroyContext(display, context)
    }

    @Test
    fun reservation_startsAtTheFirstSlot() {
        assertEquals(
            "first reserved slot: another entity was created before SceneView loaded Filament",
            1,
            PrefilterEntitySlots.firstSlot
        )
    }

    @Test
    fun reservation_runsOnce() {
        val first = PrefilterEntitySlots.firstSlot
        onMain { PrefilterEntitySlots.reserve() }
        assertEquals(first, PrefilterEntitySlots.firstSlot)
    }

    @Test
    fun laterEntities_neverLandInTheReservedSlots() {
        val reserved = PrefilterEntitySlots.firstSlot until
            PrefilterEntitySlots.firstSlot + PrefilterEntitySlots.SLOT_COUNT
        onMain {
            val entities = IntArray(16)
            EntityManager.get().create(entities)
            try {
                entities.forEach {
                    val slot = it and INDEX_MASK
                    assertTrue("entity on slot $slot, inside the reserved $reserved", slot !in reserved)
                }
            } finally {
                EntityManager.get().destroy(entities)
            }
        }
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
