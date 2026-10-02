package io.github.sceneview.loaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Collections

/**
 * Pins the lookup behind [MaterialLoader.destroyMaterialInstance] (sceneview/sceneview#4285).
 *
 * `ImageNode.destroy()` passed back `materialInstance`, which Filament rebuilds as a new Java
 * wrapper on every read. `MaterialLoader` matched on identity only, so the instance was never
 * destroyed, and the next frame after its texture was freed aborted with `Invalid texture still
 * bound to MaterialInstance`. That is the ML Object Label crash at the 7th label.
 *
 * A Filament `MaterialInstance` cannot be built on the JVM (its constructor calls into JNI), so
 * [Wrapper] stands in: like Filament's wrapper it does not override `equals`, and it reads its
 * native pointer through a getter that throws once the object is destroyed.
 */
class MaterialInstanceTrackingTest {

    /** Filament-like handle wrapper: identity `equals`, throwing getter once destroyed. */
    private class Wrapper(private var pointer: Long) {
        val nativeObject: Long
            get() {
                check(pointer != 0L) { "Calling method on destroyed MaterialInstance" }
                return pointer
            }

        fun clear() {
            pointer = 0L
        }
    }

    private fun tracked(vararg pointers: Long): MutableList<Wrapper> =
        Collections.synchronizedList(pointers.map { Wrapper(it) }.toMutableList())

    @Test
    fun aReadBackWrapperFindsTheTrackedInstance() {
        val list = tracked(0x10, 0x20, 0x30)
        val created = list[1]
        // What `RenderableManager.getMaterialInstanceAt` returns: same pointer, new object.
        val readBack = Wrapper(0x20)

        val removed = list.removeTracked(readBack) { it.nativeObject }

        assertSame("the loader's own wrapper must come back, so it is the one destroyed", created, removed)
        assertEquals(listOf(0x10L, 0x30L), list.map { it.nativeObject })
    }

    @Test
    fun theCreatedWrapperStillMatchesByIdentity() {
        val list = tracked(0x10, 0x20)
        val created = list[0]

        assertSame(created, list.removeTracked(created) { it.nativeObject })
        assertEquals(1, list.size)
    }

    @Test
    fun aSecondDestroyIsANoOp() {
        val list = tracked(0x10)
        val readBack = Wrapper(0x10)
        list.removeTracked(readBack) { it.nativeObject }!!.clear()

        // The same alias again (a double destroy): nothing left to match, nothing removed.
        assertNull(list.removeTracked(readBack) { it.nativeObject })
    }

    @Test
    fun aDestroyedWrapperMatchesNothing() {
        val list = tracked(0x10, 0x20)
        val destroyed = Wrapper(0x20).apply { clear() }

        assertNull(list.removeTracked(destroyed) { it.nativeObject })
        assertEquals(2, list.size)
    }

    @Test
    fun anUntrackedInstanceIsIgnored() {
        val list = tracked(0x10, 0x20)

        assertNull(list.removeTracked(Wrapper(0x99)) { it.nativeObject })
        assertEquals(2, list.size)
    }

    @Test
    fun aDestroyedEntryInTheListDoesNotBreakTheLookup() {
        // An instance orphaned by a Material destroy keeps its slot but no pointer.
        val list = tracked(0x10, 0x20).apply { this[0].clear() }

        val removed = list.removeTracked(Wrapper(0x20)) { it.nativeObject }

        assertEquals(0x20L, removed?.nativeObject)
        assertEquals(1, list.size)
    }
}
