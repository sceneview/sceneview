package io.github.sceneview.loaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The calls a [ModelLoader] must refuse before they reach gltfio. Each of these, in the field, was
 * a `PreconditionPanic` or a read of freed memory inside the JNI: an abort of the whole app, which
 * no `try`/`catch` on the Java side can stop. The loader asks [LoaderLifecycle] first.
 */
class LoaderLifecycleTest {

    private var engineAlive = true
    private val lifecycle = LoaderLifecycle { engineAlive }

    @Test
    fun `a fresh loader can create and pump its load`() {
        assertTrue(lifecycle.canCreate)
        assertTrue(lifecycle.canTouchNatives)
        lifecycle.checkCanCreate("create a model")
    }

    @Test
    fun `no model is created once a destroy was asked for`() {
        assertTrue(lifecycle.requestDestroy())

        assertFalse(lifecycle.canCreate)
        assertThrowsIllegalState("this ModelLoader was destroyed") { lifecycle.checkCanCreate("create a model") }
    }

    @Test
    fun `the load is still pumped between the destroy request and the release`() {
        // A destroy waits for a model still decoding its textures, and that wait is what pumps them.
        lifecycle.requestDestroy()

        assertTrue(lifecycle.canTouchNatives)
    }

    @Test
    fun `nothing touches the loader's natives once they are freed`() {
        lifecycle.requestDestroy()
        assertTrue(lifecycle.release())

        // The render loop's next frame calls updateLoad: asyncUpdateLoad on a freed ResourceLoader.
        assertFalse(lifecycle.canTouchNatives)
        assertFalse(lifecycle.canCreate)
    }

    @Test
    fun `a second destroy does nothing`() {
        assertTrue(lifecycle.requestDestroy())

        assertFalse(lifecycle.requestDestroy())
    }

    @Test
    fun `nothing is created on a destroyed engine`() {
        engineAlive = false

        assertFalse(lifecycle.canCreate)
        assertFalse(lifecycle.canTouchNatives)
        assertThrowsIllegalState("Engine of this ModelLoader was destroyed") {
            lifecycle.checkCanCreate("create a model")
        }
    }

    @Test
    fun `the natives are not freed again after the engine took them`() {
        lifecycle.requestDestroy()
        engineAlive = false

        assertFalse("destroying a loader calls into its freed engine", lifecycle.release())
        assertFalse(lifecycle.canTouchNatives)
    }

    @Test
    fun `the refusal names what was refused`() {
        lifecycle.requestDestroy()

        assertThrowsIllegalState("Cannot create an instance") { lifecycle.checkCanCreate("create an instance") }
    }

    private fun assertThrowsIllegalState(message: String, block: () -> Unit) {
        try {
            block()
            fail("expected an IllegalStateException")
        } catch (error: IllegalStateException) {
            assertTrue("'${error.message}' should mention '$message'", error.message.orEmpty().contains(message))
        }
    }

    @Test
    fun `the engine is asked each time, not once`() {
        assertTrue(lifecycle.canCreate)
        engineAlive = false
        assertEquals(false, lifecycle.canCreate)
    }
}
