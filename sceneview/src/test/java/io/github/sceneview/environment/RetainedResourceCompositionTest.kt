package io.github.sceneview.environment

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Compose wiring of [rememberRetainedResource] — what `rememberHDREnvironment` and
 * `rememberKTXEnvironment` are made of — run in a real composition with a fake resource.
 *
 * It replaces the compiled-shape guard for #2458 ("the previous Environment leaks on key
 * change"): that one could only check that a disposal lambda existed. This one counts releases
 * across a swap, across the two frames a replaced resource is kept, and across leaving the
 * composition in the middle of that window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RetainedResourceCompositionTest {

    @Test
    fun `the first load publishes and releases nothing`() = runTest {
        val host = Host(this)

        assertNull(host.value)
        host.finishLoad("a", Resource("a"))

        assertEquals("a", host.value?.name)
        assertTrue(host.released.isEmpty())
    }

    @Test
    fun `a key change keeps the current resource until the replacement is ready`() = runTest {
        val host = Host(this)
        val first = Resource("a")
        host.finishLoad("a", first)

        host.key = "b"
        host.frame()

        assertSame(first, host.value)
        assertTrue(host.released.isEmpty())
    }

    @Test
    fun `a replaced resource is released once, two frames after the swap`() = runTest {
        val host = Host(this)
        val first = Resource("a")
        val second = Resource("b")
        host.finishLoad("a", first)
        host.key = "b"
        host.frame()

        host.finishLoad("b", second)
        assertSame(second, host.value)
        assertTrue("released in the frame that swapped", host.released.isEmpty())
        host.frame()
        assertTrue("released one frame after the swap", host.released.isEmpty())
        host.frame()
        assertEquals(listOf(first), host.released)

        repeat(3) { host.frame() }
        assertEquals(listOf(first), host.released)
    }

    @Test
    fun `leaving the composition right after a swap releases both resources once`() = runTest {
        val host = Host(this)
        val first = Resource("a")
        val second = Resource("b")
        host.finishLoad("a", first)
        host.key = "b"
        host.frame()
        host.finishLoad("b", second)
        // `first` is retired and still alive: its two frames have not elapsed.
        assertTrue(host.released.isEmpty())

        host.present = false
        host.frame()

        assertEquals(listOf(second, first), host.released)
        repeat(3) { host.frame() }
        assertEquals(listOf(second, first), host.released)
    }

    @Test
    fun `a request superseded while loading never replaces the value`() = runTest {
        val host = Host(this)
        val first = Resource("a")
        val last = Resource("c")
        host.finishLoad("a", first)

        host.key = "b"
        host.frame()
        host.key = "c"
        host.frame()
        // The load for "b" was cancelled with its request: completing it changes nothing.
        host.finishLoad("b", Resource("b"))
        assertSame(first, host.value)

        host.finishLoad("c", last)
        assertSame(last, host.value)
        repeat(2) { host.frame() }
        assertEquals(listOf(first), host.released)
    }

    @Test
    fun `leaving the composition while the first load runs releases nothing`() = runTest {
        val host = Host(this)

        host.present = false
        host.frame()
        host.finishLoad("a", Resource("a"))

        assertNull(host.value)
        assertTrue(host.released.isEmpty())
    }

    private class Resource(val name: String) {
        override fun toString(): String = name
    }

    /** A composition with no UI: one [rememberRetainedResource] call, driven frame by frame. */
    private class Host(private val scope: TestScope) {
        private val clock = BroadcastFrameClock()
        private val loads = mutableMapOf<String, CompletableDeferred<Resource?>>()
        private var frameTime = 0L

        val released = mutableListOf<Resource>()
        var key by mutableStateOf("a")
        var present by mutableStateOf(true)
        var value: Resource? = null
            private set

        init {
            val recomposer = Recomposer(scope.backgroundScope.coroutineContext + clock)
            scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            Composition(NoNodes(), recomposer).setContent {
                value = if (present) {
                    val requested = key
                    rememberRetainedResource(
                        owner = this,
                        requested,
                        release = { released += it },
                    ) { load(requested).await() }
                } else {
                    null
                }
            }
            settle()
        }

        private fun load(key: String) = loads.getOrPut(key) { CompletableDeferred() }

        /** Completes the load for [key] and lets the composition observe the result. */
        fun finishLoad(key: String, resource: Resource?) {
            load(key).complete(resource)
            frame()
        }

        /** One frame: pending state writes are recomposed, then frame-awaiting effects resume. */
        fun frame() {
            settle()
            clock.sendFrame(frameTime++)
            settle()
        }

        /**
         * Runs what is ready, then reports the state it wrote — what Android's global snapshot
         * manager does on its own — so the recomposer is waiting for the next frame.
         */
        private fun settle() {
            repeat(2) {
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
            }
        }
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
