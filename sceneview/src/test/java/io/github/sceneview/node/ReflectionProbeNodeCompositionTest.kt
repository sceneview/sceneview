package io.github.sceneview.node

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.google.android.filament.IndirectLight
import com.google.android.filament.Scene
import io.github.sceneview.environment.Environment
import io.github.sceneview.math.Position
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The four defects of #4928, reproduced with the real [ReflectionProbeNode] composable in a real
 * composition, over a Filament [Scene] and [IndirectLight]s without JNI (Robolectric).
 *
 * The scene's own environment is declared the way `SceneView`'s environment effect does it: the
 * same two registry calls, keyed on the same `(scene, environment)` pair.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.google.android.filament"])
class ReflectionProbeNodeCompositionTest {

    private val day = IndirectLight(1L)
    private val night = IndirectLight(2L)
    private val office = IndirectLight(3L)
    private val garage = IndirectLight(4L)

    private val inOffice = Position(0f, 0f, 1f)
    private val outside = Position(50f, 0f, 0f)

    @Test
    fun `1 - priority decides, not composition order`() = runTest {
        val host = Host(this)
        host.a = Environment(office) to 2
        host.b = Environment(garage) to 1
        host.frame()

        assertSame(office, host.scene.indirectLight)

        host.b = Environment(garage) to 3
        host.frame()
        assertSame(garage, host.scene.indirectLight)
    }

    @Test
    fun `2 - leaving the zone hands the scene its environment back`() = runTest {
        val host = Host(this)
        host.a = Environment(office) to 0
        host.aRadius = 3f
        host.camera = inOffice
        host.frame()
        assertSame(office, host.scene.indirectLight)

        host.camera = outside
        host.frame()
        assertSame(day, host.scene.indirectLight)
    }

    @Test
    fun `3 - a new scene environment keeps the active probe`() = runTest {
        val host = Host(this)
        host.a = Environment(office) to 0
        host.frame()
        assertSame(office, host.scene.indirectLight)

        // A dark/light toggle in the app: `SceneView` gets a new environment.
        host.environment = Environment(night)
        host.frame()
        assertSame(office, host.scene.indirectLight)

        host.a = null
        host.frame()
        assertSame(night, host.scene.indirectLight)
    }

    @Test
    fun `4 - chained probes removed in order end on the scene's environment`() = runTest {
        val host = Host(this)
        host.a = Environment(office) to 0
        host.frame()
        host.b = Environment(garage) to 0
        host.frame()
        assertSame(garage, host.scene.indirectLight)

        host.a = null
        host.frame()
        assertSame(garage, host.scene.indirectLight)
        host.b = null
        host.frame()
        assertSame(day, host.scene.indirectLight)
    }

    @Test
    fun `4 - a probe light destroyed meanwhile is not handed back`() = runTest {
        val host = Host(this)
        host.a = Environment(office) to 0
        host.b = Environment(garage) to 0
        host.frame()

        // The environment the first probe showed is destroyed by its owner (a key change).
        office.destroyWithoutJni()
        host.b = null
        host.frame()

        assertSame(day, host.scene.indirectLight)
    }

    @Test
    fun `leaving the composition empties the scene, like SceneView did before`() = runTest {
        val host = Host(this)
        host.a = Environment(office) to 0
        host.frame()

        host.present = false
        host.frame()

        assertNull(host.scene.indirectLight)
    }

    /** What `Engine.destroyIndirectLight` leaves behind: a wrapper whose handle getter throws. */
    private fun IndirectLight.destroyWithoutJni() {
        IndirectLight::class.java.getDeclaredMethod("clearNativeObject")
            .apply { isAccessible = true }
            .invoke(this)
    }

    /** A composition with no UI: an environment effect and up to two probes, frame by frame. */
    private inner class Host(private val scope: TestScope) {
        private val clock = BroadcastFrameClock()
        private var frameTime = 0L

        val scene: Scene = Scene::class.java.getDeclaredConstructor(Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(1L)

        var present by mutableStateOf(true)
        var environment by mutableStateOf(Environment(day))
        var camera by mutableStateOf(Position(0f, 0f, 0f))

        /** Probe A then probe B, in composition order: its environment and priority, or absent. */
        var a by mutableStateOf<Pair<Environment, Int>?>(null)
        var b by mutableStateOf<Pair<Environment, Int>?>(null)
        var aRadius by mutableStateOf(0f)

        init {
            val recomposer = Recomposer(scope.backgroundScope.coroutineContext + clock)
            scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            Composition(NoNodes(), recomposer).setContent {
                if (present) {
                    val environment = environment
                    // `SceneView`'s environment effect.
                    DisposableEffect(scene, environment) {
                        scene.reflectionProbes.setEnvironmentLight(environment.indirectLight)
                        onDispose { scene.reflectionProbes.releaseEnvironmentLight(environment.indirectLight) }
                    }
                    a?.let { (env, priority) ->
                        ReflectionProbeNode(
                            filamentScene = scene,
                            environment = env,
                            radius = aRadius,
                            priority = priority,
                            cameraPosition = camera,
                        )
                    }
                    b?.let { (env, priority) ->
                        ReflectionProbeNode(
                            filamentScene = scene,
                            environment = env,
                            priority = priority,
                            cameraPosition = camera,
                        )
                    }
                }
            }
            frame()
        }

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
}
