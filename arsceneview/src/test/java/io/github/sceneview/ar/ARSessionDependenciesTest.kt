package io.github.sceneview.ar

import android.content.ComponentName
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedFace
import com.google.ar.core.Camera
import com.google.ar.core.Earth
import com.google.ar.core.Frame
import com.google.ar.core.GeospatialPose
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.FatalException
import io.github.sceneview.ar.arcore.ARSession
import io.github.sceneview.ar.arcore.rememberCameraGeospatialPose
import io.github.sceneview.ar.arcore.rememberDetectedPlanes
import io.github.sceneview.ar.arcore.rememberEarthState
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.node.AugmentedFaceNode
import io.github.sceneview.ar.node.ReticleNode
import io.github.sceneview.gesture.MoveGestureDetector
import io.github.sceneview.ar.scene.OwnedVisualizers
import io.github.sceneview.ar.scene.PlaneRenderer
import io.github.sceneview.node.Node
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
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import java.util.IdentityHashMap
import java.nio.FloatBuffer
import java.nio.ShortBuffer

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
        ARSessionDependenciesTest.NativeAugmentedFace::class,
        ARSessionDependenciesTest.NativeEarth::class,
        ARSessionDependenciesTest.NativeTrackable::class,
        SessionDependencyShadows.EngineHandles::class,
        SessionDependencyShadows.EntityHandles::class,
    ],
)
class ARSessionDependenciesTest {
    private lateinit var arCore: ARCore
    private lateinit var context: Context
    private lateinit var engine: Engine
    private val nodes = mutableListOf<Node>()
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
        reportedPlanes.clear()
        nativeTrackableReads = 0
        nativeFaceReads = 0
        reportedEarths.clear()
        cameraPoses.clear()
        nativeEarthReads = 0
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
    fun `a retained face node skips its closed face after fatal failure and retry`() {
        arCore.resume(context, null)
        val firstSession = arCore.session!!
        val firstFace = nativeObject(AugmentedFace::class.java, firstSession)
        val updates = mutableListOf<AugmentedFace>()
        val trackingChanges = mutableListOf<TrackingState>()
        val node = AugmentedFaceNode(
            engine,
            firstFace,
            onTrackingStateChanged = { trackingChanges += it },
            onUpdated = { updates += it },
        ).also { nodes += it }
        val firstFrame = frame(firstSession, 1_000_000_000L)
        nextFrame = firstFrame
        assertSame(firstFrame, arCore.updateSessionOrNull())
        node.update(firstSession, firstFrame)
        assertEquals(listOf(firstFace), updates)
        assertTrue(nativeFaceReads > 0)
        val readsBeforeFailure = nativeFaceReads

        updateFailure = FatalException("EGL context creation failed")
        assertNull(arCore.updateSessionOrNull())
        assertTrue(firstSession.isClosed)
        assertEquals(1, failures.size)
        assertNull(node.trackable)
        assertNull(node.session)
        assertNull(node.frame)
        assertEquals(TrackingState.STOPPED, node.trackingState)
        assertFalse(node.isVisible)
        assertEquals(listOf(TrackingState.TRACKING, TrackingState.STOPPED), trackingChanges)

        updateFailure = null
        arCore.retrySession()
        val replacement = arCore.session!!
        assertNotSame(firstSession, replacement)
        val replacementFrame = frame(replacement, 2_000_000_000L)
        nextFrame = replacementFrame
        assertSame(replacementFrame, arCore.updateSessionOrNull())
        node.update(replacement, replacementFrame)
        assertNull(node.session)
        assertNull(node.frame)
        assertEquals(readsBeforeFailure, nativeFaceReads)
        assertEquals(listOf(firstFace), updates)

        // Retaining the old node must not stop faces from the replacement session updating.
        val replacementFace = nativeObject(AugmentedFace::class.java, replacement)
        val replacementNode = AugmentedFaceNode(engine, replacementFace, onUpdated = { updates += it })
            .also { nodes += it }
        replacementNode.update(replacement, replacementFrame)
        assertEquals(listOf(firstFace, replacementFace), updates)
        assertSame(replacementFrame, replacementNode.frame)

        arCore.destroy()
        val readsAfterClose = nativeFaceReads
        node.destroy()
        replacementNode.destroy()
        assertEquals(readsAfterClose, nativeFaceReads)
        assertTrue(node.isDestroyed)
        assertTrue(replacementNode.isDestroyed)
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
        val face = nativeObject(AugmentedFace::class.java, session)
        arCore.destroy()

        assertThrows(IllegalStateException::class.java) { frame.timestamp }
        assertThrows(IllegalStateException::class.java) { node.anchor.detach() }
        assertThrows(IllegalStateException::class.java) { node.anchor.pose }
        assertThrows(IllegalStateException::class.java) { plane.trackingState }
        assertThrows(IllegalStateException::class.java) { face.trackingState }
        assertThrows(IllegalStateException::class.java) { face.meshVertices }
    }

    @Test
    fun `ending an automatic drag after a fatal update cancels pending movement safely`() {
        arCore.resume(context, null)
        val session = arCore.session!!
        val node = anchorNode(session, automatic = true)
        node.update(session, frame(session, 1_000_000_000L))
        val plane = nativeObject(Plane::class.java, session)
        ReflectionHelpers.setField(node, "pending", AutoPlacementCandidate(plane, Pose.IDENTITY, null))
        ReflectionHelpers.setField(node, "dragging", true)
        ReflectionHelpers.setField(node, "offset", floatArrayOf(1f, 0f, 0f))
        ReflectionHelpers.setField(node, "interruptedMove", true)
        updateFailure = FatalException("EGL context creation failed")
        assertNull(arCore.updateSessionOrNull())
        assertTrue(session.isClosed)

        val detector = MoveGestureDetector(context, object : MoveGestureDetector.SimpleOnMoveListener {})
        val event = MotionEvent.obtain(0L, 100L, MotionEvent.ACTION_UP, 0f, 0f, 0)
        val pendingAfterClose = ReflectionHelpers.getField<Any>(node, "pending")
        val offsetAfterClose = ReflectionHelpers.getField<Any>(node, "offset")
        val draggingAfterClose = ReflectionHelpers.getField<Boolean>(node, "dragging")
        val interruptedAfterClose = ReflectionHelpers.getField<Boolean>(node, "interruptedMove")
        try {
            node.onMoveEnd(detector, event)
            assertNull("Invalidation must clear the pending native candidate before the late event", pendingAfterClose)
            assertNull(offsetAfterClose)
            assertFalse(draggingAfterClose)
            assertFalse(interruptedAfterClose)
            assertNull(ReflectionHelpers.getField<Any>(node, "pending"))
            assertNull(ReflectionHelpers.getField<Any>(node, "offset"))
            assertFalse(ReflectionHelpers.getField<Boolean>(node, "dragging"))
            assertFalse(ReflectionHelpers.getField<Boolean>(node, "interruptedMove"))
        } finally {
            event.recycle()
        }
    }

    @Test
    fun `closing a session clears a frozen reticle hit and notifies its callback before close`() {
        arCore.resume(context, null)
        val session = arCore.session!!
        val changes = mutableListOf<HitResult?>()
        val reticle = ReticleNode(engine, 0f, 0f, onHitResultChanged = {
            assertFalse("The callback must run while its session is open", session.isClosed)
            changes += it
        }).apply { update = false }
        // Seed the retained native hit directly: this test targets the release setter,
        // without simulating ARCore's raycast or reading the hit's native pose.
        val hit = allocateWithoutJni(HitResult::class.java)
        ReflectionHelpers.setField(reticle, "hitResult", hit)
        reticle.trackable = nativeObject(Plane::class.java, session)
        arCore.onSessionClosing = {
            releaseSessionNodes(listOf(reticle))
            planes.resetSession()
        }
        try {
            updateFailure = FatalException("EGL context creation failed")
            assertNull(arCore.updateSessionOrNull())
            assertTrue(session.isClosed)
            assertNull(reticle.trackable)
            assertNull(reticle.hitResult)
            assertEquals(listOf<HitResult?>(null), changes)

            updateFailure = null
            arCore.retrySession()
            val replacement = arCore.session!!
            reticle.update(replacement, frame(replacement, 2_000_000_000L))
            assertNull("A frozen reticle must not retain a hit from the old session", reticle.hitResult)
        } finally {
            reticle.destroy()
        }
    }

    @Test
    fun `plane discovery clears its state and stops native reads after a fatal close`() {
        arCore.resume(context, null)
        val session = arCore.session!!
        val plane = nativeObject(Plane::class.java, session)
        reportedPlanes[session] = listOf(plane)
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).addActivityIfNotPresent(
            ComponentName(app.packageName, ComponentActivity::class.java.name)
        )
        val rule = createComposeRule()
        var detected: State<List<Plane>>? = null
        val body = object : Statement() {
            override fun evaluate() {
                rule.mainClock.autoAdvance = false
                rule.setContent { detected = rememberDetectedPlanes(session) }
                rule.mainClock.advanceTimeByFrame()
                var readsBeforeClose = 0
                rule.runOnIdle {
                    assertEquals(listOf(plane), detected!!.value)
                    assertTrue(nativeTrackableReads > 0)
                    readsBeforeClose = nativeTrackableReads
                    updateFailure = FatalException("EGL context creation failed")
                    assertNull(arCore.updateSessionOrNull())
                    assertTrue(session.isClosed)
                }
                repeat(3) { rule.mainClock.advanceTimeByFrame() }
                rule.runOnIdle {
                    assertTrue(detected!!.value.isEmpty())
                    assertEquals(readsBeforeClose, nativeTrackableReads)
                }
            }
        }
        rule.apply(body, Description.createTestDescription(javaClass, "closed plane discovery")).evaluate()
    }

    @Test
    fun `geospatial state clears and stops native reads after a fatal close`() {
        arCore.resume(context, null)
        val session = arCore.session!!
        // Their real constructors run, so Robolectric attaches the shadows below.
        val earth = sessionObject(Earth::class.java, session)
        val cameraPose = sessionObject(GeospatialPose::class.java, session)
        reportedEarths[session] = earth
        cameraPoses[earth] = cameraPose
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).addActivityIfNotPresent(
            ComponentName(app.packageName, ComponentActivity::class.java.name)
        )
        val rule = createComposeRule()
        var earthState: State<Earth.EarthState?>? = null
        var pose: State<GeospatialPose?>? = null
        // Composed only after the close: the guard before the first frame must hold too.
        var composeLate by mutableStateOf(false)
        var lateEarthState: State<Earth.EarthState?>? = null
        var latePose: State<GeospatialPose?>? = null
        val body = object : Statement() {
            override fun evaluate() {
                rule.mainClock.autoAdvance = false
                rule.setContent {
                    earthState = rememberEarthState(session)
                    pose = rememberCameraGeospatialPose(session)
                    if (composeLate) {
                        lateEarthState = rememberEarthState(session)
                        latePose = rememberCameraGeospatialPose(session)
                    }
                }
                rule.mainClock.advanceTimeByFrame()
                var readsBeforeClose = 0
                rule.runOnIdle {
                    assertEquals(Earth.EarthState.ENABLED, earthState!!.value)
                    assertSame(cameraPose, pose!!.value)
                    assertTrue(nativeEarthReads > 0)
                    readsBeforeClose = nativeEarthReads
                    updateFailure = FatalException("EGL context creation failed")
                    assertNull(arCore.updateSessionOrNull())
                    assertTrue(session.isClosed)
                    composeLate = true
                }
                repeat(3) { rule.mainClock.advanceTimeByFrame() }
                rule.runOnIdle {
                    assertNull(earthState!!.value)
                    assertNull(pose!!.value)
                    assertNull(lateEarthState!!.value)
                    assertNull(latePose!!.value)
                    assertEquals(readsBeforeClose, nativeEarthReads)
                }
            }
        }
        rule.apply(body, Description.createTestDescription(javaClass, "closed geospatial state")).evaluate()
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

    /** An ARCore wrapper whose only constructor is `(nativeHandle, session)`. */
    private fun <T : Any> sessionObject(type: Class<T>, session: ARSession): T =
        type.getDeclaredConstructor(java.lang.Long.TYPE, Session::class.java)
            .apply { isAccessible = true }
            .newInstance(nextNativeHandle++, session)
            .also { owners[it] = session }

    private fun <T : Any> nativeObject(type: Class<T>, session: ARSession): T =
        type.getDeclaredConstructor().apply { isAccessible = true }.newInstance().also {
            ReflectionHelpers.setField(it, "nativeHandle", nextNativeHandle++)
            owners[it] = session
        }

    @Implements(Session::class)
    class NativeSession {
        @RealObject private lateinit var real: Session
        @Implementation
        protected fun <T : com.google.ar.core.Trackable> getAllTrackables(type: Class<T>): Collection<T> {
            check(!(real as ARSession).isClosed) { "Native getAllTrackables after session close" }
            nativeTrackableReads++
            return (reportedPlanes[real] ?: emptyList()).map { requireNotNull(type.cast(it)) }
        }
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
        @Implementation protected fun getEarth(): Earth? {
            check(!(real as ARSession).isClosed) { "Native getEarth after session close" }
            nativeEarthReads++
            return reportedEarths[real]
        }
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
        @Implementation protected open fun getTrackingState(): TrackingState = open(real) {
            if (real is AugmentedFace) nativeFaceReads++
            TrackingState.TRACKING
        }
    }

    @Implements(Earth::class)
    class NativeEarth : NativeTrackable() {
        @RealObject private lateinit var real: Earth
        // Earth declares its own getTrackingState(), so TrackableBase's shadow never sees it.
        @Implementation override fun getTrackingState(): TrackingState = open(real) {
            nativeEarthReads++
            TrackingState.TRACKING
        }
        @Implementation protected fun getEarthState(): Earth.EarthState = open(real) {
            nativeEarthReads++
            Earth.EarthState.ENABLED
        }
        @Implementation protected fun getCameraGeospatialPose(): GeospatialPose = open(real) {
            nativeEarthReads++
            cameraPoses.getValue(real)
        }
    }

    @Implements(AugmentedFace::class)
    class NativeAugmentedFace : NativeTrackable() {
        @RealObject private lateinit var real: AugmentedFace
        // An initial TRACKING frame may have empty mesh buffers. Exercise real node updates
        // without GPU allocation; every face read still checks the native session lifetime.
        @Implementation protected fun getMeshTriangleIndices(): ShortBuffer = open(real) {
            nativeFaceReads++
            ShortBuffer.allocate(0)
        }
        @Implementation protected fun getMeshVertices(): FloatBuffer = open(real) {
            nativeFaceReads++
            FloatBuffer.allocate(0)
        }
        @Implementation protected fun getMeshNormals(): FloatBuffer = open(real) {
            nativeFaceReads++
            FloatBuffer.allocate(0)
        }
        @Implementation protected fun getMeshTextureCoordinates(): FloatBuffer = open(real) {
            nativeFaceReads++
            FloatBuffer.allocate(0)
        }
    }

    private companion object {
        val owners = IdentityHashMap<Any, ARSession>()
        val cameras = IdentityHashMap<Frame, Camera>()
        val timestamps = IdentityHashMap<Frame, Long>()
        val detached = mutableListOf<Anchor>()
        var nextFrame: Frame? = null
        var updateFailure: Exception? = null
        var nextNativeHandle = 1L
        val reportedPlanes = IdentityHashMap<Session, List<Plane>>()
        var nativeTrackableReads = 0
        var nativeFaceReads = 0
        val reportedEarths = IdentityHashMap<Session, Earth>()
        val cameraPoses = IdentityHashMap<Earth, GeospatialPose>()
        var nativeEarthReads = 0

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
