package io.github.sceneview.ar.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [LazyOwned] holds the deprecated [PlaneRenderer.planeTexture]: it must never be `null` to a
 * caller, must not be built unless read, and must be released exactly once.
 */
class LazyOwnedTest {

    private var created = 0
    private val released = mutableListOf<Any>()

    private fun owned() = LazyOwned(
        create = { Any().also { created++ } },
        release = { released += it },
    )

    @Test
    fun `release without a read builds nothing`() {
        owned().release()
        assertEquals(0, created)
        assertEquals(0, released.size)
    }

    @Test
    fun `repeated reads build one value`() {
        val owned = owned()
        val first = owned.value
        assertSame(first, owned.value)
        assertEquals(1, created)
    }

    @Test
    fun `release after a read destroys that value once`() {
        val owned = owned()
        val value = owned.value
        owned.release()
        owned.release()
        assertEquals(listOf(value), released)
    }

    @Test(expected = IllegalStateException::class)
    fun `read after release fails instead of leaking a new value`() {
        val owned = owned()
        owned.release()
        owned.value
    }
}
