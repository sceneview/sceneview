package io.github.sceneview

import android.content.ComponentName
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Renderer
import io.github.sceneview.node.FilamentWithoutJni
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression for #5133 / #3898: real SceneRenderer touch listeners on SurfaceView and TextureView,
 * through AndroidView's interop filter in the test rule's ComposeView, inside a verticalScroll.
 * Gestures are injected through Compose, never dispatched directly to the surface listener.
 *
 * A full SceneView cannot start here: Engine.Builder.sharedContext rejects Robolectric's EGLContext
 * with "Invalid shared context". Only Filament's native handles are stubbed, using the existing
 * FilamentWithoutJni fixture. These tests cover scroll position and the delivered/cancelled touch
 * stream, not SceneView's parameter-to-renderer wiring, the render loop, or rendered camera orbit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["com.google.android.filament"])
class SceneComposeParentInterceptionTest {
    @get:Rule(order = 0)
    val registerActivity = object : ExternalResource() {
        override fun before() {
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context.packageManager).addActivityIfNotPresent(
                ComponentName(context.packageName, ComponentActivity::class.java.name),
            )
        }
    }

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun surfaceViewOptInKeepsComposeParentStill() =
        assertDragPriority(SurfaceType.Surface, disallowParentIntercept = true)

    @Test
    fun surfaceViewDefaultLetsComposeParentScroll() =
        assertDragPriority(SurfaceType.Surface, disallowParentIntercept = false)

    @Test
    fun textureViewOptInKeepsComposeParentStill() =
        assertDragPriority(SurfaceType.TextureSurface, disallowParentIntercept = true)

    @Test
    fun textureViewDefaultLetsComposeParentScroll() =
        assertDragPriority(SurfaceType.TextureSurface, disallowParentIntercept = false)

    private fun assertDragPriority(surfaceType: SurfaceType, disallowParentIntercept: Boolean) {
        lateinit var scroll: ScrollState
        lateinit var renderer: SceneRenderer
        val events = mutableListOf<Int>()
        composeRule.setContent {
            scroll = rememberScrollState()
            Column(Modifier.size(300.dp).verticalScroll(scroll)) {
                AndroidView(
                    modifier = Modifier.size(200.dp).testTag("scene"),
                    factory = { context ->
                        renderer = createRenderer().apply {
                            this.disallowParentIntercept = disallowParentIntercept
                        }
                        @Suppress("DEPRECATION")
                        val display = context.getSystemService(WindowManager::class.java).defaultDisplay
                        val onTouch: (MotionEvent) -> Unit = { events += it.actionMasked }
                        when (surfaceType) {
                            SurfaceType.Surface -> SurfaceView(context).also {
                                renderer.attachToSurfaceView(it, true, context, display, onTouch)
                            }
                            SurfaceType.TextureSurface -> TextureView(context).also {
                                renderer.attachToTextureView(it, true, context, display, onTouch)
                            }
                        }
                    },
                    onRelease = { renderer.destroy() },
                )
                Spacer(Modifier.height(900.dp))
            }
        }
        composeRule.runOnIdle {
            assertTrue("The parent must have room to scroll", scroll.maxValue > 0)
            assertEquals(0, scroll.value)
        }

        composeRule.onNodeWithTag("scene").performTouchInput { swipeUp() }

        composeRule.runOnIdle {
            assertEquals(MotionEvent.ACTION_DOWN, events.first())
            if (disallowParentIntercept) {
                assertEquals("The scene must keep the vertical drag", 0, scroll.value)
                assertTrue(MotionEvent.ACTION_MOVE in events)
                assertEquals(MotionEvent.ACTION_UP, events.last())
                assertFalse(MotionEvent.ACTION_CANCEL in events)
            } else {
                assertTrue("The Compose parent must scroll", scroll.value > 0)
                assertEquals(MotionEvent.ACTION_CANCEL, events.last())
                assertFalse(MotionEvent.ACTION_UP in events)
            }
        }
    }

    private fun createRenderer(): SceneRenderer {
        val engine = FilamentWithoutJni.engine()
        val view = com.google.android.filament.View::class.java
            .getDeclaredConstructor(Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1L)
        val renderer = Renderer::class.java.getDeclaredConstructor(
            Long::class.javaPrimitiveType, com.google.android.filament.Engine::class.java,
        ).apply { isAccessible = true }.newInstance(1L, engine)
        return SceneRenderer(engine, view, renderer)
    }
}
