package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.ClippedViewports
import io.github.sceneview.SceneRenderer
import io.github.sceneview.ViewportPadding
import io.github.sceneview.math.Position
import io.github.sceneview.render.RenderTestHarness
import io.github.sceneview.utils.scaling
import io.github.sceneview.utils.screenToRay
import io.github.sceneview.utils.shift
import io.github.sceneview.utils.viewToRay
import io.github.sceneview.utils.worldToScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Real Filament viewport/projection state; all JNI stays on the main thread. */
@RunWith(AndroidJUnit4::class)
class ContentPaddingClipTest {
    @Test
    fun paddingClipToggleAndSurfaceResizeKeepViewportAndPickingInSync() {
        val harness = RenderTestHarness(width = 400, height = 800)
        try {
            harness.runOnMain {
                val camera = CameraNode(harness.engine)
                val renderer = SceneRenderer(harness.engine, harness.view, harness.renderer)
                try {
                    harness.view.camera = camera.camera
                    camera.setView(harness.view)
                    renderer.viewportCamera = camera
                    renderer.onSurfaceResized = { _, _ -> camera.updateProjection() }
                    camera.worldPosition = Position(0f, 0f, 4f)
                    camera.lookAt(Position())
                    val padding = ViewportPadding(40f, 80f, 60f, 200f)
                    camera.contentPadding = padding
                    fun viewport(left: Int, bottom: Int, width: Int, height: Int) {
                        val vp = harness.view.viewport
                        assertEquals(left, vp.left)
                        assertEquals(bottom, vp.bottom)
                        assertEquals(width, vp.width)
                        assertEquals(height, vp.height)
                    }
                    viewport(0, 0, 400, 800)
                    val unclippedRay = harness.view.screenToRay(190f, 340f)!!
                    camera.clipToContentPadding = true
                    viewport(40, 200, 300, 520)
                    assertEquals(300.0 / 520.0, camera.getViewPortAspect(), 1e-9)
                    assertEquals(1f, camera.camera.scaling.x, 0f)
                    assertEquals(1f, camera.camera.scaling.y, 0f)
                    assertEquals(Float2(0f), camera.camera.shift)
                    val onScreen = harness.view.worldToScreen(Position())!!
                    assertEquals(190f, onScreen.x, 1e-3f)
                    assertEquals(340f, onScreen.y, 1e-3f)
                    val normalized = Float2(190f / 400f, 1f - 340f / 800f)
                    val rays = listOf(harness.view.screenToRay(190f, 340f)!!,
                        camera.viewToRay(normalized), camera.camera.viewToRay(normalized))
                    rays.forEach { ray ->
                        for (i in 0..2) {
                            assertEquals(normalize(unclippedRay.direction)[i],
                                normalize(ray.direction)[i], 1e-5f)
                        }
                        val atSubject = ray.origin + ray.direction * (-ray.origin.z / ray.direction.z)
                        assertEquals(0f, atSubject.x, 1e-4f)
                        assertEquals(0f, atSubject.y, 1e-4f)
                    }
                    camera.contentPadding = padding.copy(top = 100f, bottom = 300f)
                    viewport(40, 300, 300, 400)
                    renderer.applyResize(800, 1000)
                    viewport(40, 300, 700, 600)
                    assertEquals(700.0 / 600.0, camera.getViewPortAspect(), 1e-9)
                    camera.clipToContentPadding = false
                    viewport(0, 0, 800, 1000)
                    assertEquals(0.6f, camera.camera.scaling.y, 1e-6f)
                    assertNull(ClippedViewports.get(harness.view))
                    camera.clipToContentPadding = true
                    camera.contentPadding = ViewportPadding.Zero
                    viewport(0, 0, 800, 1000)
                    assertEquals(1f, camera.camera.scaling.y, 0f)
                    assertEquals(Float2(0f), camera.camera.shift)
                } finally {
                    renderer.destroy()
                    camera.destroy()
                }
            }
        } finally {
            harness.destroy()
        }
    }

    @Test
    fun destroyingAnOldCameraDoesNotRemoveItsReplacementsCoordinates() {
        val harness = RenderTestHarness(width = 400, height = 800)
        try {
            harness.runOnMain {
                val old = CameraNode(harness.engine)
                val replacement = CameraNode(harness.engine)
                try {
                    harness.view.camera = old.camera
                    old.setView(harness.view)
                    old.contentPadding = ViewportPadding(bottom = 200f)
                    old.clipToContentPadding = true
                    // SceneView releases the clip before handing its View to the new camera.
                    old.clipToContentPadding = false
                    harness.view.camera = replacement.camera
                    replacement.setView(harness.view)
                    replacement.contentPadding = ViewportPadding(top = 100f, bottom = 300f)
                    replacement.clipToContentPadding = true
                    old.destroy()
                    assertEquals(800, ClippedViewports.get(harness.view)!!.surfaceHeight)
                    assertEquals(400, harness.view.viewport.height)
                    replacement.destroy()
                    assertNull(ClippedViewports.get(harness.view))
                    assertEquals(800, harness.view.viewport.height)
                } finally {
                    old.destroy()
                    replacement.destroy()
                }
            }
        } finally {
            harness.destroy()
        }
    }
}
