package io.github.sceneview

import android.content.Context
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.google.android.filament.Renderer
import io.github.sceneview.node.FilamentWithoutJni
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the installed surface listeners without a native window or Filament JNI (#3898). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], instrumentedPackages = ["com.google.android.filament"])
class SceneParentGestureInterceptionTest {
    @Test
    fun optInKeepsDragsOutOfAnInterceptingParent() = forEachSurface { renderer, parent, _, events ->
        renderer.disallowParentIntercept = true

        parent.send(MotionEvent.ACTION_DOWN)
        parent.send(MotionEvent.ACTION_MOVE)
        parent.send(MotionEvent.ACTION_UP)

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), events)
        assertEquals(listOf(true, false), parent.requests)
    }

    @Test
    fun defaultLetsTheParentInterceptDrags() = forEachSurface { _, parent, _, events ->
        parent.send(MotionEvent.ACTION_DOWN)
        parent.send(MotionEvent.ACTION_MOVE)

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), events)
        assertEquals(emptyList<Boolean>(), parent.requests)
    }

    @Test
    fun priorityIsRequestedBeforeDownAndReleasedAfterUpOrCancel() {
        for (terminal in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            forEachSurface { renderer, parent, surface, events ->
                renderer.disallowParentIntercept = true

                surface.send(MotionEvent.ACTION_DOWN)
                surface.send(MotionEvent.ACTION_MOVE)
                surface.send(terminal)

                assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, terminal), events)
                // The claim precedes DOWN, and release follows terminal delivery.
                assertEquals(listOf(emptyList<Int>(), events.toList()), parent.eventsAtRequest)
                assertEquals(listOf(true, false), parent.requests)
            }
        }
    }

    @Test
    fun changingTheOptionAffectsTheNextStreamWithoutReattachingTheSurface() =
        forEachSurface { renderer, parent, _, events ->
            renderer.disallowParentIntercept = true
            parent.send(MotionEvent.ACTION_DOWN)
            parent.send(MotionEvent.ACTION_UP)
            renderer.disallowParentIntercept = false
            events.clear()
            parent.requests.clear()

            parent.send(MotionEvent.ACTION_DOWN)
            parent.send(MotionEvent.ACTION_MOVE)

            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), events)
            assertEquals(emptyList<Boolean>(), parent.requests)
        }

    @Test
    fun disablingTheOptionMidStreamStillReleasesItsClaim() =
        forEachSurface { renderer, parent, surface, _ ->
            renderer.disallowParentIntercept = true
            surface.send(MotionEvent.ACTION_DOWN)
            renderer.disallowParentIntercept = false
            surface.send(MotionEvent.ACTION_CANCEL)

            assertEquals(listOf(true, false), parent.requests)
        }

    private fun forEachSurface(
        check: (SceneRenderer, InterceptingParent, View, MutableList<Int>) -> Unit
    ) {
        for (surfaceType in SurfaceType.entries) {
            val context = RuntimeEnvironment.getApplication()
            val engine = FilamentWithoutJni.engine()
            val filamentView = com.google.android.filament.View::class.java
                .getDeclaredConstructor(Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(1L)
            val filamentRenderer = Renderer::class.java.getDeclaredConstructor(
                Long::class.javaPrimitiveType, com.google.android.filament.Engine::class.java
            ).apply { isAccessible = true }.newInstance(1L, engine)
            val renderer = SceneRenderer(engine, filamentView, filamentRenderer)
            val events = mutableListOf<Int>()
            val parent = InterceptingParent(context, events)
            @Suppress("DEPRECATION")
            val display = context.getSystemService(WindowManager::class.java).defaultDisplay
            val dispatch: (MotionEvent) -> Unit = { events += it.actionMasked }
            val surface = when (surfaceType) {
                SurfaceType.Surface -> SurfaceView(context).also {
                    renderer.attachToSurfaceView(it, true, context, display, dispatch)
                }
                SurfaceType.TextureSurface -> TextureView(context).also {
                    renderer.attachToTextureView(it, true, context, display, dispatch)
                }
            }
            parent.addView(surface, FrameLayout.LayoutParams(300, 300))
            parent.layout(0, 0, 300, 300)
            surface.layout(0, 0, 300, 300)
            try {
                check(renderer, parent, surface, events)
            } finally {
                renderer.destroy()
            }
        }
    }

    private class InterceptingParent(context: Context, private val events: List<Int>) : FrameLayout(context) {
        val requests = mutableListOf<Boolean>()
        val eventsAtRequest = mutableListOf<List<Int>>()

        override fun onInterceptTouchEvent(event: MotionEvent) = event.actionMasked == MotionEvent.ACTION_MOVE

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            requests += disallowIntercept
            eventsAtRequest += events.toList()
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }

    private fun View.send(action: Int) {
        val event = MotionEvent.obtain(0L, 16L, action, 10f, 10f, 0)
        try {
            dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
