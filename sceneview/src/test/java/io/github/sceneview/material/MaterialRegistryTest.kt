package io.github.sceneview.material

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The record behind [materialOrNull], run with plain stand-ins for Filament's wrappers.
 *
 * Filament 1.77 wraps every instance it reads back from a native pointer (all glTF instances,
 * `getMaterialInstanceAt`) without its Material, so `getMaterial()` returns null and the demo
 * crashed on `Material.hasParameter`. The registry answers by native handle, so any wrapper of a
 * recorded instance finds its Material, and nothing destroyed is ever handed out.
 */
class MaterialRegistryTest {

    /** A wrapper: a fresh object per read-back, same native handle; 0 once destroyed. */
    private class Wrapper(var handle: Long) {
        val nativeObject: Long get() = check(handle != 0L) { "destroyed" }.let { handle }
    }

    private val registry = NativeHandleRegistry<Wrapper, Wrapper>({ it.nativeObject }, { it.nativeObject })

    @Test
    fun `a read-back wrapper of a recorded instance finds its material`() {
        val material = Wrapper(100)
        registry.register(Wrapper(1), material)

        assertSame(material, registry.materialOf(Wrapper(1)))
        assertNull(registry.materialOf(Wrapper(2)))
    }

    @Test
    fun `a destroyed instance or material is never answered`() {
        val material = Wrapper(100)
        val instance = Wrapper(1)
        registry.register(instance, material)

        instance.handle = 0
        assertNull("destroyed instance wrapper", registry.materialOf(instance))

        material.handle = 0
        assertNull("destroyed material", registry.materialOf(Wrapper(1)))
    }

    @Test
    fun `forgetting a material forgets every instance recorded against it`() {
        val kept = Wrapper(100)
        val dropped = Wrapper(200)
        registry.register(Wrapper(1), dropped)
        registry.register(Wrapper(2), dropped)
        registry.register(Wrapper(3), kept)

        registry.forgetMaterial(dropped)

        assertNull(registry.materialOf(Wrapper(1)))
        assertNull(registry.materialOf(Wrapper(2)))
        assertSame(kept, registry.materialOf(Wrapper(3)))
    }

    @Test
    fun `an instance's handles are forgotten with the asset that claimed them`() {
        val ownership = InstanceOwnership<Any> { registry.forgetHandles(it) }
        val material = Wrapper(100)
        val first = Any()
        val second = Any()

        listOf(1L, 2L).forEach { registry.register(Wrapper(it), material)?.let(ownership::record) }
        ownership.claim(first)
        registry.register(Wrapper(3), material)?.let(ownership::record)
        ownership.claim(second)
        // A second FilamentInstance of the first asset adds to what it owns.
        registry.register(Wrapper(4), material)?.let(ownership::record)
        ownership.claim(first)

        ownership.release(first)
        assertEquals(listOf(null, null, material, null), (1L..4L).map { registry.materialOf(Wrapper(it)) })

        ownership.releaseAll()
        assertEquals(0, registry.size)
    }

    @Test
    fun `a failed load forgets what it recorded`() {
        val ownership = InstanceOwnership<Any> { registry.forgetHandles(it) }
        registry.register(Wrapper(1), Wrapper(100))?.let(ownership::record)

        ownership.claim(null)

        assertNull(registry.materialOf(Wrapper(1)))
    }
}
