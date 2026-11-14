package io.github.sceneview.ar

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.FatalException
import io.github.sceneview.ar.arcore.ARSession
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.scene.OwnedVisualizers
import io.github.sceneview.ar.scene.PlaneRenderer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import java.util.IdentityHashMap

/**
 * Runs the real ARCore failure/retry path, AnchorNode lifecycle and PlaneRenderer frame-cache
 * logic. Only ARCore native calls and Filament allocation/rendering are replaced. Every native
 * object records its owning session: reading it once close begins fails the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    instrumentedPackages = ["com.google.ar.core", "com.google.android.filament"],
    shadows = [
        ARSessionDependenciesTest.NativeSession::class,
        ARSessionDependenciesTest.NativeFrame::class,
        ARSessionDependenciesTest.NativeCamera::class,
        ARSessionDependenciesTest.NativeAnchor::class,
        ARSessionDependenciesTest.NativePlane::class,
        ARSessionDependenciesTest.NativeTrackable::class,
        SessionDependencyShadows.EngineHandles::class,
        SessionDependencyShadows.EntityHandles::class,
    ],
)
class ARSessionDependenciesTest {
    private lateinit var arCore: ARCore
    private lateinit var context: Context
    private lateinit var engine: Engine
    private val nodes = mutableListOf<AnchorNode>()
    private val failures = mutableListOf<Exception>()
    private lateinit var planes: PlaneRenderer
    private lateinit var visualizers: OwnedVisualizers<Plane, PlaneVisualizer, MaterialInstance>
    private var releasedVisualizers = 0

    @Before
    fun setUp() {
        owners.clear()
        cameras.clear()
        timestamps.clear()
        detached.clear()
        nextNativeHandle = 1L
        updateFailure = null
        nextFrame = null
        val app = RuntimeEnvironment.getApplication()
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        context = app.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        engine = Engine::class.java.getDeclaredConstructor(java.lang.Long.TYPE, Engine.Config::class.java)
            .apply { isAccessible = true }.newInstance(1L, null)
        // Skip material loading and GPU allocation, retaining the renderer's real update/reset
        // methods and its real registry. Empty updated-plane lists never allocate GPU geometry.
        planes = allocateWithoutJni(PlaneRenderer::class.java)
        visualizers = OwnedVisualizers(
            destroyVisualizer = { open(it) { releasedVisualizers++ } },
            destroyInstance = {},
        )
        ReflectionHelpers.setField(planes, "visualizers", visualizers)
        planes.isEnabled = true
        planes.maxUpdatesPerSecond = 10
        arCore = ARCore({}, {}, {}, { failures += it }, { _, _ -> }).apply {
            checkCameraPermission = false
            features = emptySet()
            onSessionClosing = {
                releaseSessionNodes(nodes)
                planes.resetSession()
            }
        }
    }

    @After
    fun tearDown() {
        arCore.detachHost()
        arCore.destroy()
        nodes.forEach { it.destroy() }
    }

    @Test
    fun `a valid frame then fatal update then retry and exit never read a closed session`() {
        arCore.resume(context, null)
        val firstSession = arCore.session!!
        val firstFrame = frame(firstSession, 1_000_000_000L)
        val firstNode = anchorNode(firstSession, automatic = true)
        val firstAnchor = firstNode.anchor
        firstNode.isEditable = true
        firstNode.update(firstSession, firstFrame)
        nextFrame = firstFrame
        assertSame(firstFrame, arCore.updateSessionOrNull())
        planes.update(firstSession, firstFrame)
        assertSame(firstFrame, ReflectionHelpers.getField<Frame>(planes, "frame"))
        val oldPlane = nativeObject(Plane::class.java, firstSession)
        val oldVisualizer = allocateWithoutJni(PlaneVisualizer::class.java)
        owners[oldVisualizer] = firstSession
        visualizers.put(oldPlane, oldVisualizer, emptyList())
        updateFailure = FatalException("EGL context creation failed")

        assertNull(arCore.updateSessionOrNull())

        assertTrue(firstSession.isClosed)
        assertEquals(listOf(firstNode.anchor), detached)
        assertNull(firstNode.session)
        assertNull(firstNode.frame)
        assertEquals(TrackingState.STOPPED, firstNode.trackingState)
        assertFalse(firstNode.isPositionEditable)
        assertEquals(0, visualizers.size)
        assertNull(ReflectionHelpers.getField<Frame>(planes, "frame"))
        assertEquals(1, releasedVisualizers)
        assertEquals(1, failures.size)
        updateFailure = null
        arCore.retrySession()
        val secondSession = arCore.session!!
        assertNotSame(firstSession, secondSession)
        val secondFrame = frame(secondSession, 2_000_000_000L)
        nextFrame = secondFrame
        assertSame(secondFrame, arCore.updateSessionOrNull())
        // Without resetSession this reads firstFrame.timestamp through a closed session.
        planes.update(secondSession, secondFrame)
        assertSame(secondFrame, ReflectionHelpers.getField<Frame>(planes, "frame"))
        firstNode.update(secondSession, secondFrame)
        assertNull(firstNode.frame)
        val replacementAnchor = nativeObject(Anchor::class.java, secondSession)
        firstNode.anchor = replacementAnchor
        firstNode.update(secondSession, secondFrame)
        assertSame(secondFrame, firstNode.frame)
        assertEquals(TrackingState.TRACKING, firstNode.trackingState)
        assertTrue(firstNode.isPositionEditable)
        val secondNode = anchorNode(secondSession)
        secondNode.update(secondSession, secondFrame)

        arCore.destroy()
        firstNode.destroy()
        secondNode.destroy()

        assertTrue(secondSession.isClosed)
        assertEquals(listOf(firstAnchor, replacementAnchor, secondNode.anchor), detached)
        assertSame(firstAnchor, detached[0])
        assertSame(replacementAnchor, detached[1])
        assertSame(secondNode.anchor, detached[2])
        assertNull(secondNode.frame)
        assertTrue(firstNode.isDestroyed)
        assertTrue(secondNode.isDestroyed)
        assertFalse(ShadowLog.getLogs().any { it.msg == "PlaneRenderer update error" })
    }

    @Test
    fun `placement controllers release anchors before close and later disposal is safe`() {
        arCore.resume(context, null)
        val session = arCore.session!!
        val controller = PlacementController()
        val placedAnchor = nativeObject(Anchor::class.java, session)
        controller.add(placedAnchor)
        val autoAnchor = nativeObject(Anchor::class.java, session)
        val result = AutoPlacementResult(autoAnchor, nativeObject(Plane::class.java, session), Pose.IDENTITY, null)
        session.onClosing {
            controller.clear()
            result.releaseAnchor()
        }

        arCore.destroy()
        controller.clear()
        result.releaseAnchor()

        assertEquals(0, controller.count)
        assertEquals(listOf(placedAnchor, autoAnchor), detached)
    }

    @Test
    fun `the native test doubles reject frame and anchor access after close begins`() {
        arCore.resume(context, null)
        val session = arCore.session!!
        val frame = frame(session, 1_000_000_000L)
        val node = anchorNode(session)
        val plane = nativeObject(Plane::class.java, session)
        arCore.destroy()

        assertThrows(IllegalStateException::class.java) { frame.timestamp }
        assertThrows(IllegalStateException::class.java) { node.anchor.detach() }
        assertThrows(IllegalStateException::class.java) { node.anchor.pose }
        assertThrows(IllegalStateException::class.java) { plane.trackingState }
    }

    private fun anchorNode(session: ARSession, automatic: Boolean = false): AnchorNode {
        val anchor = nativeObject(Anchor::class.java, session)
        val node = if (automatic) {
            val result = AutoPlacementResult(anchor, nativeObject(Plane::class.java, session), Pose.IDENTITY, null)
            val constructor = Class.forName("io.github.sceneview.ar.AutomaticAnchorNode")
                .declaredConstructors.single().apply { isAccessible = true }
            val invalidMove: (Boolean) -> Unit = {}
            constructor.newInstance(engine, result, AutoPlacementState(), invalidMove) as AnchorNode
        } else {
            AnchorNode(engine, anchor)
        }
        return node.also { nodes += it }
    }

    private fun frame(session: ARSession, timestamp: Long): Frame =
        nativeObject(Frame::class.java, session).also {
            timestamps[it] = timestamp
            cameras[it] = nativeObject(Camera::class.java, session)
        }

    private fun <T : Any> nativeObject(type: Class<T>, session: ARSession): T =
        type.getDeclaredConstructor().apply { isAccessible = true }.newInstance().also {
            ReflectionHelpers.setField(it, "nativeHandle", nextNativeHandle++)
            owners[it] = session
        }

    @Implements(Session::class)
    class NativeSession {
        @Suppress("FunctionName", "UNUSED_PARAMETER")
        @Implementation
        protected fun __constructor__(context: Context, features: Set<Session.Feature>) = Unit
        @Implementation protected fun resume() = Unit
        @Suppress("UNUSED_PARAMETER")
        @Implementation protected fun setDisplayGeometry(rotation: Int, width: Int, height: Int) = Unit
        @Implementation protected fun update(): Frame {
            updateFailure?.let { throw it }
            return checkNotNull(nextFrame)
        }
        @Implementation protected fun pause() = Unit
        @Implementation protected fun close() = Unit
        @Implementation protected fun getRecordingStatus(): RecordingStatus = RecordingStatus.NONE
    }

    @Implements(Frame::class)
    class NativeFrame {
        @RealObject private lateinit var real: Frame
        @Implementation protected fun getTimestamp(): Long = open(real) { timestamps.getValue(real) }
        @Implementation protected fun getCamera(): Camera = open(real) { cameras.getValue(real) }
        @Implementation protected fun getUpdatedAnchors(): Collection<Anchor> = open(real) { emptyList() }
        @Suppress("UNUSED_PARAMETER")
        @Implementation protected fun <T> getUpdatedTrackables(type: Class<T>): Collection<T> =
            open(real) { emptyList() }
    }

    @Implements(Camera::class)
    class NativeCamera {
        @RealObject private lateinit var real: Camera
        @Implementation protected fun getTrackingState(): TrackingState = open(real) { TrackingState.TRACKING }
    }

    @Implements(Anchor::class)
    class NativeAnchor {
        @RealObject private lateinit var real: Anchor
        @Implementation protected fun getPose(): Pose = open(real) { Pose.IDENTITY }
        @Implementation protected fun getTrackingState(): TrackingState = open(real) { TrackingState.TRACKING }
        @Implementation protected fun detach() = open(real) { detached += real }
    }

    @Implements(Plane::class)
    class NativePlane : NativeTrackable() {
        @RealObject private lateinit var real: Plane
        @Implementation protected fun getSubsumedBy(): Plane? = open(real) { null }
    }

    @Implements(className = "com.google.ar.core.TrackableBase", isInAndroidSdk = false)
    open class NativeTrackable {
        @RealObject private lateinit var real: Any
        @Implementation protected fun getTrackingState(): TrackingState = open(real) { TrackingState.TRACKING }
    }

    private companion object {
        val owners = IdentityHashMap<Any, ARSession>()
        val cameras = IdentityHashMap<Frame, Camera>()
        val timestamps = IdentityHashMap<Frame, Long>()
        val detached = mutableListOf<Anchor>()
        var nextFrame: Frame? = null
        var updateFailure: Exception? = null
        var nextNativeHandle = 1L

        fun <T> open(value: Any, block: () -> T): T {
            check(!owners.getValue(value).isClosed) { "Native access after session close: ${value.javaClass.simpleName}" }
            return block()
        }

        fun <T> allocateWithoutJni(type: Class<T>): T {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return requireNotNull(type.cast(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)))
        }
    }
}
