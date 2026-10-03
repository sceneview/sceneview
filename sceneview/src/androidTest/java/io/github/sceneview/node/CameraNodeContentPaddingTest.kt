package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import dev.romainguy.kotlin.math.Float2
import io.github.sceneview.ViewportPadding
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.createView
import io.github.sceneview.math.Position
import io.github.sceneview.safeDestroy
import io.github.sceneview.utils.projectionTransform
import io.github.sceneview.utils.shift
import io.github.sceneview.utils.viewToRay
import io.github.sceneview.utils.worldToView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.tan

/**
 * `contentPadding` against the real Filament camera.
 *
 * The JVM tests pin the maths; this pins the two facts they have to assume about Filament:
 *
 * 1. `Camera.getProjectionMatrix` / `getCullingProjectionMatrix` return the projection **without**
 *    the scaling and the shift, which is why picking through them missed by the offset;
 * 2. a [CameraNode] with a padding reports, through the SDK's own conversions, a subject on the
 *    optical axis at the centre of the visible area — and a touch there comes back to the subject;
 * 3. a padding rebuilds the default lens only: a field of view, an orthographic or a custom
 *    projection the caller set is still the camera's projection afterwards.
 */
@RunWith(AndroidJUnit4::class)
class CameraNodeContentPaddingTest {

    private lateinit var engine: Engine
    private lateinit var filamentView: View
    private lateinit var cameraNode: CameraNode

    private val width = 1080
    private val height = 2400
    private val sheet = ViewportPadding(top = 200f, bottom = 1200f)

    @Before
    fun setup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Gltfio.init(); Filament.init(); Utils.init()
            engine = createEngine(createEglContext())
            filamentView = createView(engine)
            filamentView.viewport = Viewport(0, 0, width, height)
            cameraNode = CameraNode(engine)
            cameraNode.setView(filamentView)
            cameraNode.worldPosition = Position(0f, 0f, 4f)
            cameraNode.lookAt(Position(0f, 0f, 0f))
            cameraNode.updateProjection()
        }
    }

    @After
    fun teardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cameraNode.destroy()
            engine.destroyView(filamentView)
            engine.safeDestroy()
        }
    }

    @Test
    fun filamentProjectionGettersLeaveTheScalingAndShiftOut() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val camera = cameraNode.camera
            val before = camera.projectionTransform
            camera.setScaling(0.5, 0.25)
            camera.setShift(0.1, 0.2)
            val after = camera.projectionTransform
            assertEquals(before, after)
            camera.setScaling(1.0, 1.0)
            camera.setShift(0.0, 0.0)
        }
    }

    @Test
    fun aPaddingBecomesTheCamerasScalingShiftAndAspect() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cameraNode.contentPadding = sheet
            val scaling = DoubleArray(4).also { cameraNode.camera.getScaling(it) }
            assertEquals(1.0, scaling[0], 1e-9)
            assertEquals(1000.0 / 2400.0, scaling[1], 1e-9)
            assertEquals(0f, cameraNode.camera.shift.x, 1e-6f)
            assertEquals(500f / 2400f, cameraNode.camera.shift.y, 1e-6f)
            assertEquals(1080.0 / 1000.0, cameraNode.getViewPortAspect(), 1e-9)

            cameraNode.contentPadding = ViewportPadding.Zero
            cameraNode.camera.getScaling(scaling)
            assertEquals(1.0, scaling[1], 1e-9)
            assertEquals(0f, cameraNode.camera.shift.y, 0f)
            assertEquals(1080.0 / 2400.0, cameraNode.getViewPortAspect(), 1e-9)
        }
    }

    @Test
    fun theSubjectOnTheAxisIsAtTheCentreOfTheVisibleAreaAndATouchThereFindsIt() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cameraNode.contentPadding = sheet
            val subject = Position(0f, 0f, 0f)

            val onScreen = cameraNode.camera.worldToView(subject)
            assertNotNull(onScreen)
            // Visible area: y from 200 to 1200 px, centred at 700 px from the top.
            assertEquals(540f, onScreen!!.x * width, 1f)
            assertEquals(700f, (1f - onScreen.y) * height, 1f)

            val ray = cameraNode.camera.viewToRay(Float2(540f / width, 1f - 700f / height))
            // The ray starts on the near plane and runs down the optical axis: it passes through
            // the subject, 4 units ahead of the camera.
            val t = (subject.z - ray.origin.z) / ray.direction.z
            val hitX = ray.origin.x + ray.direction.x * t
            val hitY = ray.origin.y + ray.direction.y * t
            assertTrue("ray misses the subject: x=$hitX y=$hitY", hitX * hitX + hitY * hitY < 1e-4f)
        }
    }

    @Test
    fun aCallersOwnShiftIsLeftAloneWhenNoPaddingWasEverSet() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cameraNode.camera.setShift(0.25, 0.0)
            cameraNode.updateProjection()
            assertEquals(0.25f, cameraNode.camera.shift.x, 1e-6f)
        }
    }

    // ── A projection the caller set is not replaced by the default lens ──────────────────────────

    @Test
    fun aFieldOfViewSetByTheCallerSurvivesAPaddingAndFollowsTheVisibleAspect() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cameraNode.setProjection(fovInDegrees = 30.0)
            val focal = 1.0 / tan(Math.toRadians(15.0))
            assertEquals(focal, cameraNode.camera.projectionTransform.y.y.toDouble(), 1e-4)

            cameraNode.contentPadding = sheet
            val padded = cameraNode.camera.projectionTransform
            // Still 30° vertically — not the 28 mm lens, whose term would be ~2.33.
            assertEquals(focal, padded.y.y.toDouble(), 1e-4)
            // And rebuilt for the visible area, so pixels stay square.
            assertEquals(focal / (1080.0 / 1000.0), padded.x.x.toDouble(), 1e-4)
            val scaling = DoubleArray(4).also { cameraNode.camera.getScaling(it) }
            assertEquals(1000.0 / 2400.0, scaling[1], 1e-9)

            cameraNode.contentPadding = ViewportPadding.Zero
            val restored = cameraNode.camera.projectionTransform
            assertEquals(focal, restored.y.y.toDouble(), 1e-4)
            assertEquals(focal / (1080.0 / 2400.0), restored.x.x.toDouble(), 1e-4)
        }
    }

    @Test
    fun aFieldOfViewWithItsOwnAspectIsLeftExactlyAsSet() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cameraNode.setProjection(fovInDegrees = 30.0, aspect = 2.0)
            val before = cameraNode.camera.projectionTransform
            cameraNode.contentPadding = sheet
            assertEquals(before, cameraNode.camera.projectionTransform)
            assertEquals(500f / 2400f, cameraNode.camera.shift.y, 1e-6f)
        }
    }

    @Test
    fun anOrthographicProjectionIsKeptAndOnlyScaledAndShifted() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cameraNode.setProjection(Camera.Projection.ORTHO, -2.0, 2.0, -1.0, 1.0, 0.1, 100.0)
            val before = cameraNode.camera.projectionTransform
            cameraNode.contentPadding = sheet
            assertEquals(before, cameraNode.camera.projectionTransform)
            val scaling = DoubleArray(4).also { cameraNode.camera.getScaling(it) }
            assertEquals(1000.0 / 2400.0, scaling[1], 1e-9)
            assertEquals(500f / 2400f, cameraNode.camera.shift.y, 1e-6f)

            cameraNode.contentPadding = ViewportPadding.Zero
            assertEquals(before, cameraNode.camera.projectionTransform)
            cameraNode.camera.getScaling(scaling)
            assertEquals(1.0, scaling[1], 1e-9)
        }
    }

    @Test
    fun theDefaultLensIsRebuiltAgainOnceUpdateProjectionTakesOver() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val lens = cameraNode.camera.projectionTransform
            cameraNode.setProjection(fovInDegrees = 30.0)
            cameraNode.updateProjection()
            assertEquals(lens, cameraNode.camera.projectionTransform)

            cameraNode.contentPadding = sheet
            val padded = cameraNode.camera.projectionTransform
            assertEquals(lens.y.y, padded.y.y, 1e-5f)
            assertEquals(lens.y.y / (1080f / 1000f), padded.x.x, 1e-4f)
        }
    }
}
