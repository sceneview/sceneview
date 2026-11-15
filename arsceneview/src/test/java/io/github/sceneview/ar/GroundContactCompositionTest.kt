package io.github.sceneview.ar

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Exercises the public entry point in a real composition, without creating Filament resources. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GroundContactCompositionTest {

    @Test
    fun `custom placement registers a world-space floor contact`() = runTest {
        val host = Host(this)
        assertTrue(host.read())
        assertArrayEquals(floatArrayOf(0f, 0f, -2f, 0.3f, 0f, 1f, 0f, 1f), host.out, 1e-6f)
        host.dispose()
    }

    @Test
    fun `center is read each frame without recomposition and null suspends the contact`() = runTest {
        val host = Host(this)
        var center: Position? = null
        var reads = 0
        host.provider = { reads++; center }
        host.frame()
        assertEquals(0, reads)
        assertFalse(host.read())

        center = Position(1f, 0f, -3f)
        assertTrue(host.read())
        assertEquals(1f, host.out[0], 0f)
        assertEquals(-3f, host.out[2], 0f)
        assertEquals(2, reads)

        center = null
        assertFalse(host.read())
        assertArrayEquals(FloatArray(8), host.out, 0f)
        host.dispose()
    }

    @Test
    fun `recomposition uses the latest provider radius and normal and removes the old contact`() = runTest {
        val host = Host(this)
        assertTrue(host.read())

        host.provider = { Position(0f, 0f, -4f) }
        host.radius = 0.8f
        host.normal = Direction(z = 2f)
        host.frame()

        // A leaked old registration would win because it is nearer the camera.
        assertTrue(host.read())
        assertArrayEquals(floatArrayOf(0f, 0f, -4f, 0.8f, 0f, 0f, 1f, 1f), host.out, 1e-6f)
        host.present = false
        host.frame()
        assertFalse(host.read())
        assertArrayEquals(FloatArray(8), host.out, 0f)
        host.dispose()
    }

    @Test
    fun `disposing the composition unregisters its contact`() = runTest {
        val host = Host(this)
        assertTrue(host.read())
        host.dispose()
        assertFalse(host.read())
        assertArrayEquals(FloatArray(8), host.out, 0f)
    }

    @Test
    fun `removing the nearest public contact reveals the other registration`() = runTest {
        val host = Host(this)
        host.secondPresent = true
        host.frame()
        assertTrue(host.read())
        assertEquals(-2f, host.out[2], 0f)

        host.present = false
        host.frame()
        assertTrue(host.read())
        assertArrayEquals(floatArrayOf(0f, 0f, -5f, 0.4f, 0f, 1f, 0f, 1f), host.out, 1e-6f)
        host.dispose()
        assertFalse(host.read())
    }

    private class Host(private val test: TestScope) {
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(test.backgroundScope.coroutineContext + clock)
        private val composition = Composition(NoNodes(), recomposer)
        private var frameTime = 0L
        var present by mutableStateOf(true)
        var secondPresent by mutableStateOf(false)
        var provider by mutableStateOf<() -> Position?>({ Position(0f, 0f, -2f) })
        var radius by mutableStateOf(0.3f)
        var normal by mutableStateOf(Direction(y = 1f))
        val out = FloatArray(8)
        val scope = ARSceneScope(
            engine = withoutJni(),
            modelLoader = withoutJni(),
            materialLoader = withoutJni(),
            environmentLoader = withoutJni(),
            _nodes = mutableStateListOf(),
        )

        init {
            test.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            composition.setContent {
                if (present) scope.GroundContact(center = provider, radius = radius, normal = normal)
                if (secondPresent) scope.GroundContact(center = { Position(0f, 0f, -5f) }, radius = 0.4f)
            }
            frame()
        }

        fun read(): Boolean = scope.groundContacts.viewParameters(
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            out,
        )

        fun frame() {
            flush()
            clock.sendFrame(frameTime++)
            flush()
        }

        fun dispose() {
            composition.dispose()
            recomposer.cancel()
        }

        private fun flush() = repeat(2) {
            Snapshot.sendApplyNotifications()
            test.runCurrent()
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

/** Only the types are needed: GroundContact must never call a loader or Filament JNI. */
private inline fun <reified T> withoutJni(): T {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    return unsafeClass.getMethod("allocateInstance", Class::class.java)
        .invoke(unsafe, T::class.java) as T
}
