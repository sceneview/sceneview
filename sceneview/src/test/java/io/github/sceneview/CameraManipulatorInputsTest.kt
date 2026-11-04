package io.github.sceneview

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which inputs rebuild the manipulator `rememberCameraManipulator` returns (#4838), run in a real
 * composition with a fake manipulator (Filament's native `Manipulator` does not load on the JVM).
 *
 * The eye-position overload used to be `remember(creator)` — the keyless overload, with `creator`
 * as the calculation — so a new eye, target or creator was silently ignored and resetting the view
 * needed a `key { }` block around the call. Every rebuild matters because `SceneView` glides the
 * camera to the new manipulator's pose; every rebuild that should *not* happen matters as much,
 * because it would re-home the camera under the user's finger.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CameraManipulatorInputsTest {

    // ---------------------------------------------------------------- eye-position overload

    @Test
    fun `a new orbitHomePosition rebuilds the manipulator at that eye`() = runTest {
        val host = Host(this) { rememberCameraManipulator(orbitHomePosition = eye) { build(eye) } }
        val first = host.manipulator

        host.eye = Position(0f, 1f, 5f)
        host.frame()

        assertNotSame(first, host.manipulator)
        assertEquals(listOf(EYE, Position(0f, 1f, 5f)), host.builtWith)
    }

    @Test
    fun `an equal orbitHomePosition re-created on recomposition rebuilds nothing`() = runTest {
        val host = Host(this) { rememberCameraManipulator(orbitHomePosition = eye) { build(eye) } }
        val first = host.manipulator

        host.eye = Position(EYE.x, EYE.y, EYE.z) // equal, but a new instance
        host.frame()
        host.unrelated++
        host.frame()

        assertSame(first, host.manipulator)
        assertEquals(1, host.builtWith.size)
    }

    @Test
    fun `a new targetPosition rebuilds the manipulator`() = runTest {
        val host = Host(this) {
            rememberCameraManipulator(orbitHomePosition = EYE, targetPosition = target) { build(target) }
        }
        val first = host.manipulator

        host.target = Position(0f, 0.5f, 0f)
        host.frame()

        assertNotSame(first, host.manipulator)
        assertEquals(listOf(Position(0f), Position(0f, 0.5f, 0f)), host.builtWith)
    }

    @Test
    fun `changing key rebuilds at the same home, which is how the view is reset`() = runTest {
        val host = Host(this) {
            rememberCameraManipulator(orbitHomePosition = EYE, key = key) { build(EYE) }
        }
        val first = host.manipulator

        host.key++
        host.frame()
        val second = host.manipulator
        host.unrelated++
        host.frame()

        assertNotSame(first, second)
        assertSame("an unrelated recomposition keeps the manipulator", second, host.manipulator)
        assertEquals(listOf(EYE, EYE), host.builtWith)
    }

    @Test
    fun `a new creator alone is not a rebuild, the next key change uses it`() = runTest {
        val host = Host(this) {
            val tag = creatorTag
            rememberCameraManipulator(orbitHomePosition = EYE, key = key) { build(tag) }
        }
        val first = host.manipulator

        host.creatorTag = "custom"
        host.frame()
        assertSame("a lambda instance is not a key", first, host.manipulator)

        host.key++
        host.frame()
        assertNotSame(first, host.manipulator)
        assertEquals(listOf("stock", "custom"), host.builtWith)
    }

    // ---------------------------------------------------------------- orbitRadius overload

    @Test
    fun `the orbitRadius overload rebuilds on a new radius or key, not on an equal radius`() =
        runTest {
            val host = Host(this) {
                rememberCameraManipulator(orbitRadius = radius, key = key) { build(radius) }
            }
            val first = host.manipulator

            host.unrelated++
            host.frame()
            assertSame(first, host.manipulator)

            host.radius = 4f
            host.frame()
            val second = host.manipulator
            assertNotSame(first, second)

            host.key++
            host.frame()
            assertNotSame(second, host.manipulator)
            assertEquals(listOf(3f, 4f, 4f), host.builtWith)
        }

    // ---------------------------------------------------------------- SceneView's default

    @Test
    fun `SceneView's default manipulator ignores the live camera position it starts from`() =
        runTest {
            // SceneView passes `cameraNode.worldPosition`, which follows the manipulator: it
            // changes on every orbit, and a rebuild per recomposition would re-home the camera.
            val host = Host(this) {
                rememberDefaultCameraManipulator(initialEyePosition = eye) { build(it) }
            }
            val first = host.manipulator

            host.eye = Position(1f, 0.4f, 2.5f) // the user orbited
            host.frame()
            host.unrelated++
            host.frame()

            assertSame(first, host.manipulator)
            assertEquals(listOf(EYE), host.builtWith)
        }

    // ---------------------------------------------------------------- harness

    private class FakeManipulator : CameraGestureDetector.CameraManipulator {
        override fun setViewport(width: Int, height: Int) = Unit
        override fun getTransform(): Transform = Transform()
        override fun grabBegin(x: Int, y: Int, strafe: Boolean) = Unit
        override fun grabUpdate(x: Int, y: Int) = Unit
        override fun grabEnd() = Unit
        override fun scrollBegin(x: Int, y: Int, separation: Float) = Unit
        override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) =
            Unit
        override fun scrollEnd() = Unit
        override fun update(deltaTime: Float) = Unit
    }

    /** A composition with no UI around one manipulator call, driven frame by frame. */
    private class Host(
        private val scope: TestScope,
        private val content: @Composable Host.() -> CameraGestureDetector.CameraManipulator,
    ) {
        private val clock = BroadcastFrameClock()
        private var frameTime = 0L

        var eye by mutableStateOf(EYE)
        var target by mutableStateOf(Position(0f))
        var radius by mutableStateOf(3f)
        var key by mutableIntStateOf(0)
        var creatorTag by mutableStateOf("stock")

        /** Read by every content block, bumped to force a recomposition that changes no input. */
        var unrelated by mutableIntStateOf(0)

        /** What each [build] call was handed, in order: one entry per manipulator built. */
        val builtWith = mutableListOf<Any>()
        lateinit var manipulator: CameraGestureDetector.CameraManipulator
            private set

        fun build(input: Any): CameraGestureDetector.CameraManipulator {
            builtWith += input
            return FakeManipulator()
        }

        init {
            val recomposer = Recomposer(scope.backgroundScope.coroutineContext + clock)
            scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            Composition(NoNodes(), recomposer).setContent {
                check(unrelated >= 0) // subscribes the content to `unrelated`
                manipulator = content()
            }
            settle()
        }

        /** One frame: pending state writes are recomposed. */
        fun frame() {
            settle()
            clock.sendFrame(frameTime++)
            settle()
        }

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

    private companion object {
        val EYE = Position(0f, 0.4f, 2.75f)
    }
}
