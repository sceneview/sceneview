package io.github.sceneview.ar

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import com.google.ar.core.*
import com.google.ar.core.InstantPlacementPoint.TrackingMethod
import io.github.sceneview.haptic.ARHapticEvent
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import org.junit.Assert.*
import org.junit.Test

class AutoPlacementInstantPlacementTest {
    @Test fun `refinement during a drag rebases the release before landing on the actual floor`() {
        val (point, placement, state) = standing()
        val previous = placement.anchor.pose
        val pending = AutoPlacementCandidate(point,
            Pose(floatArrayOf(0.5f, -1.3f, -1.8f), previous.rotationQuaternion), null)
        val refined = Pose(floatArrayOf(previous.tx(), -0.8f, previous.tz()), previous.rotationQuaternion)
        (placement.anchor as FakePlacementAnchor).location = refined
        point.method = TrackingMethod.FULL_TRACKING
        val floor = TestPlane(Pose.makeTranslation(0f, -0.8f, 0f))
        state.beginAdjustment()
        updateInstantPlacement(placement, listOf(floor), state, true)
        assertEquals("Landing waits for release", 0, floor.anchorsCreated)

        val rebased = rebaseInstantPlacementDrag(pending, previous, placement.anchor.pose)
        assertArrayEquals(floatArrayOf(0.5f, -0.8f, -1.8f), rebased.pose.translation, 0.00001f)
        assertArrayEquals(pending.pose.rotationQuaternion, rebased.pose.rotationQuaternion, 0.00001f)
        val released = rebased.createAnchor()!!
        placement.anchor.detach()
        placement.anchor = released.anchor
        placement.pose = released.pose
        state.endAdjustment()
        updateInstantPlacement(placement, listOf(floor), state, true)
        assertSame("The refined floor must remain eligible after release", floor, placement.planeOrNull)
        assertEquals(1, floor.anchorsCreated)
        assertEquals(-0.8f, placement.anchor.pose.ty(), 0.00001f)
    }

    @Test fun `rebasing preserves anchor local movement and rotation without applying refinement twice`() {
        val point = FakeInstantPlacementPoint()
        val previous = Pose.makeTranslation(0f, -1.3f, -1.14f)
        val local = Pose(floatArrayOf(0.3f, 0f, -0.4f), floatArrayOf(0f, 0.4f, 0f, 0.916515f))
        val pending = AutoPlacementCandidate(point, previous.compose(local), null)
        val refined = Pose(floatArrayOf(0.2f, -0.8f, -1f), floatArrayOf(0f, 0.70710677f, 0f, 0.70710677f))
        val rebased = rebaseInstantPlacementDrag(pending, previous, refined)
        val expected = refined.compose(local)
        assertArrayEquals(expected.translation, rebased.pose.translation, 0.00001f)
        assertArrayEquals(expected.rotationQuaternion, rebased.pose.rotationQuaternion, 0.00001f)
        val unchanged = rebaseInstantPlacementDrag(rebased, refined, refined)
        assertArrayEquals(rebased.pose.translation, unchanged.pose.translation, 0.00001f)
        assertArrayEquals(rebased.pose.rotationQuaternion, unchanged.pose.rotationQuaternion, 0.00001f)
    }

    @Test fun `instant refinement does not move a pending detected plane target`() {
        val plane = TestPlane()
        val pending = AutoPlacementCandidate(plane, plane.centerPose)
        assertSame(pending, rebaseInstantPlacementDrag(pending,
            Pose.makeTranslation(0f, -1.3f, 0f), Pose.makeTranslation(0f, -0.8f, 0f)))
    }

    @Test fun `estimated floor drag rejects distant near and nonfinite camera relative targets`() {
        val point = FakeInstantPlacementPoint()
        val placement = AutoPlacementCandidate(point, point.pose, null).createAnchor()!!
        val camera = Pose.makeTranslation(12f, 1f, -5f)
        for (distance in listOf(65f, 3.01f, 0.24f, Float.NaN, Float.POSITIVE_INFINITY)) {
            var anchors = 0
            point.anchorFactory = { anchors++; FakePlacementAnchor(it) }
            val fallback = AutoPlacementCandidate(point,
                Pose.makeTranslation(camera.tx() + distance, camera.ty(), camera.tz()), null)
            val candidate = horizontalDragCandidate(placement, emptyList(),
                fromHit = { error("No plane exists") }, onContactPlane = { fallback }, cameraPose = camera)
            assertNull("Unusable distance: $distance", candidate)
            assertNull(candidate?.createAnchor())
            assertEquals("An out of range fallback must not create an anchor", 0, anchors)
        }
    }

    @Test fun `estimated floor drag accepts both distance boundaries relative to the camera`() {
        val point = FakeInstantPlacementPoint()
        val placement = AutoPlacementCandidate(point, point.pose, null).createAnchor()!!
        val camera = Pose.makeTranslation(12f, 1f, -5f)
        for (distance in listOf(0.25f, 3f)) {
            val fallback = AutoPlacementCandidate(point,
                Pose.makeTranslation(camera.tx() + distance, camera.ty(), camera.tz()), null)
            val candidate = horizontalDragCandidate(placement, emptyList(),
                fromHit = { error("No plane exists") }, onContactPlane = { fallback }, cameraPose = camera)
            assertSame(fallback, candidate)
            assertNotNull(candidate!!.createAnchor())
        }
    }

    @Test fun `instant placements can drag with no plane hits in either tracking method`() {
        for (method in TrackingMethod.entries) {
            val point = FakeInstantPlacementPoint().apply { this.method = method }
            val placement = AutoPlacementCandidate(point, point.pose, null).createAnchor()!!
            val movedPose = Pose(floatArrayOf(0.3f, -1.3f, -1.5f), placement.pose.rotationQuaternion)
            val fallback = AutoPlacementCandidate(point, movedPose, null)
            val target = horizontalDragCandidate(placement, cameraPose = Pose.IDENTITY, hits = emptyList(),
                fromHit = { error("No plane exists") }, onContactPlane = { fallback })!!
            assertSame(fallback, target)
            assertTrue(target.accepts(target.pose))
            val moved = target.createAnchor()!!
            assertSame(point, moved.trackable)
            assertArrayEquals(movedPose.translation, moved.anchor.pose.translation, 0f)
        }
    }

    @Test fun `horizontal drag prefers a usable plane to the instant contact plane`() {
        val point = FakeInstantPlacementPoint()
        val placement = AutoPlacementCandidate(point, point.pose, null).createAnchor()!!
        val plane = TestPlane()
        val hit = TestHit(plane, plane.centerPose)
        val expected = AutoPlacementCandidate(plane, hit.hitPose)
        assertSame(expected, horizontalDragCandidate(placement, cameraPose = Pose.IDENTITY, hits = listOf(hit),
            fromHit = { assertSame(hit, it); expected },
            onContactPlane = { error("A detected plane must win") }))
    }

    @Test fun `unusable hits still allow the instant contact plane fallback`() {
        val point = FakeInstantPlacementPoint()
        val placement = AutoPlacementCandidate(point, point.pose, null).createAnchor()!!
        val outside = TestPlane().apply { contains = false }
        val expected = AutoPlacementCandidate(point, point.pose, null)
        assertSame(expected, horizontalDragCandidate(placement, cameraPose = Pose.IDENTITY, hits =
            listOf(TestHit(point, point.pose), TestHit(outside, outside.centerPose)),
            fromHit = { error("Neither hit is a usable plane") }, onContactPlane = { expected }))
    }

    @Test fun `plane placements still require a hit within a tracked polygon`() {
        val plane = TestPlane()
        val placement = AutoPlacementCandidate(plane, plane.centerPose).createAnchor()!!
        val expected = AutoPlacementCandidate(plane, plane.centerPose)
        val hit = TestHit(plane, plane.centerPose)
        assertSame(expected, horizontalDragCandidate(placement, cameraPose = Pose.IDENTITY, hits = listOf(hit),
            fromHit = { expected }, onContactPlane = { error("No estimate for a plane placement") }))
        plane.contains = false
        for (hits in listOf(emptyList(), listOf(hit))) {
            assertNull(horizontalDragCandidate(placement, cameraPose = Pose.IDENTITY, hits = hits,
                fromHit = { error("No valid plane hit") },
                onContactPlane = { error("No estimate for a plane placement") }))
        }
    }

    @Test fun `an unusable contact ray or a paused instant point cannot commit a drag`() {
        val point = FakeInstantPlacementPoint()
        val placement = AutoPlacementCandidate(point, point.pose, null).createAnchor()!!
        assertNull(horizontalDragCandidate(placement, cameraPose = Pose.IDENTITY, hits = emptyList(),
            fromHit = { error("No plane exists") }, onContactPlane = { null }))
        point.state = TrackingState.PAUSED
        val candidate = horizontalDragCandidate(placement, cameraPose = Pose.IDENTITY, hits = emptyList(),
            fromHit = { error("No plane exists") },
            onContactPlane = { AutoPlacementCandidate(point, point.pose, null) })!!
        assertFalse(candidate.accepts(candidate.pose))
        assertNull(candidate.createAnchor())
    }

    @Test fun `an instant point can create a placement before any plane exists`() {
        val point = FakeInstantPlacementPoint()
        val result = AutoPlacementCandidate(point, point.pose, null).createAnchor()
        assertNotNull("instant placement must not require a Plane polygon", result)
        assertNull(result!!.planeOrNull)
        assertThrows(IllegalStateException::class.java) { result.plane }
    }

    @Test fun `a paused instant point cannot create an anchor`() {
        val point = FakeInstantPlacementPoint().apply { state = TrackingState.PAUSED }
        assertNull(AutoPlacementCandidate(point, point.pose, null).createAnchor())
    }

    @Test fun `a failed instant anchor does not consume the placement request`() {
        val point = FakeInstantPlacementPoint()
        val candidate = AutoPlacementCandidate(point, point.pose, null)
        point.anchorFactory = { FakePlacementAnchor(it).apply { state = TrackingState.PAUSED } }
        val state = AutoPlacementState().apply { requestPlacement() }
        assertEquals(FrameEffect.NONE, state.onFrame(FrameInput(0, true, true)) { candidate.createAnchor() != null })
        assertTrue(state.wantsSurface)
        point.anchorFactory = { FakePlacementAnchor(it) }
        assertEquals(FrameEffect.PLACE, state.onFrame(FrameInput(16, true, true)) { candidate.createAnchor() != null })
        assertEquals(1, state.placementsCreated)
    }

    @Test fun `only opted in horizontal scenes enable LOCAL_Y_UP`() {
        assertEquals(Config.InstantPlacementMode.DISABLED,
            AutoPlacementInstantPlacement.mode(false, PlacementSurface.SURFACE))
        assertEquals(Config.InstantPlacementMode.LOCAL_Y_UP,
            AutoPlacementInstantPlacement.mode(true, PlacementSurface.SURFACE))
        assertEquals(Config.InstantPlacementMode.DISABLED,
            AutoPlacementInstantPlacement.mode(true, PlacementSurface.WALL))
    }

    @Test fun `fallback waits exactly 600 milliseconds of continuous tracking`() {
        val delay = AutoPlacementInstantPlacement()
        assertFalse(delay.ready(0, false))
        assertFalse(delay.ready(1_000, true))
        assertFalse(delay.ready(1_599, true))
        assertTrue(delay.ready(1_600, true))
        assertFalse(delay.ready(2_000, false))
        assertFalse(delay.ready(3_000, true))
        assertFalse(delay.ready(3_599, true))
        assertTrue(delay.ready(3_600, true))
        // A session pause or a consumed placement cannot spend the next placement's delay.
        delay.reset()
        assertFalse(delay.ready(10_000, true))
        assertTrue(delay.ready(10_600, true))
    }

    @Test fun `reach matches iOS for preview real size and unusable sizes`() {
        assertEquals(1.14f, AutoPlacementInstantPlacement.reach(0.3f), 0.00001f)
        assertEquals(2.5f, AutoPlacementInstantPlacement.reach(2f), 0.00001f)
        assertEquals(3f, AutoPlacementInstantPlacement.reach(20f), 0f)
        for (side in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(1f, AutoPlacementInstantPlacement.reach(side), 0f)
        }
    }

    @Test fun `the floor estimate stays grounded and within the size dependent reach`() {
        val eye = Position(0f)
        fun point(direction: Position) = AutoPlacementInstantPlacement.floorPoint(eye, direction, -1.3f, 2f)!!
        assertEquals(-1.3f, point(Position(0f, 0f, -1f)).y, 0f)
        assertEquals(-2.5f, point(Position(0f, 0f, -1f)).z, 0f)
        assertEquals(-1.5f, point(Position(0f, -1f, -0.1f)).z, 0.00001f)
        assertEquals(-2.5f, point(Position(0f, 1f, -1f)).z, 0f)
        assertNull(AutoPlacementInstantPlacement.floorPoint(eye, Position(0f, -1f, 0f), -1.3f, 2f))
    }

    @Test fun `disabled delayed and wall flows never request an instant hit`() {
        val frame = TestFrame()
        assertNull(find(frame, enabled = false))
        assertNull(find(frame, due = false))
        assertNull(find(frame, surface = PlacementSurface.WALL))
        assertEquals(0, frame.instantHits)
    }

    @Test fun `a detected plane wins even before the delay`() {
        val plane = TestPlane()
        val frame = TestFrame().apply { hits = listOf(TestHit(plane, plane.centerPose)) }
        assertSame(plane, find(frame, due = false)!!.plane)
        assertEquals(0, frame.instantHits)
    }

    @Test fun `fallback passes the floor distance to ARCore and waits through empty hits`() {
        val frame = TestFrame()
        val candidate = find(frame)!!
        assertNull(candidate.planeOrNull)
        assertEquals(1, frame.instantHits)
        assertEquals(kotlin.math.sqrt(1.3f * 1.3f + 1.14f * 1.14f), frame.distance, 0.00001f)
        frame.instantResults = emptyList()
        assertNull(find(frame))
    }

    @Test fun `a tracked floor below the camera replaces the phone height guess but a table does not`() {
        val frame = TestFrame()
        val floor = TestPlane(Pose.makeTranslation(0f, -2f, 0f))
        findInstantPlacementSurface(frame, listOf(floor), 100, 100, 0.3f)
        assertEquals(kotlin.math.sqrt(4f + 1.14f * 1.14f), frame.distance, 0.00001f)
        val table = TestPlane(Pose.makeTranslation(0f, -0.2f, 0f))
        findInstantPlacementSurface(frame, listOf(table), 100, 100, 0.3f)
        assertEquals(kotlin.math.sqrt(1.3f * 1.3f + 1.14f * 1.14f), frame.distance, 0.00001f)
    }

    @Test fun `untracked and zero size viewports never run an instant hit test`() {
        val frame = TestFrame()
        assertNull(findInstantPlacementSurface(frame, emptyList(), 0, 100, 0.3f))
        frame.cameraState = TrackingState.PAUSED
        assertNull(findInstantPlacementSurface(frame, emptyList(), 100, 100, 0.3f))
        assertEquals(0, frame.instantHits)
    }

    @Test fun `tracking method starts full for a plane and clears on reset or dismissal`() {
        val state = AutoPlacementState()
        assertNull(state.trackingMethod)
        state.requestPlacement()
        state.onFrame(FrameInput(0, true, true))
        assertEquals(TrackingMethod.FULL_TRACKING, state.trackingMethod)
        state.resetPlacement(1)
        assertNull(state.trackingMethod)
        state.onFrame(FrameInput(2, true, true))
        state.dismiss()
        assertNull(state.trackingMethod)
    }

    @Test fun `full tracking lands once and retains the request count and refined yaw`() {
        val (point, placement, state) = standing()
        val plane = TestPlane()
        val old = placement.anchor as FakePlacementAnchor
        val events = mutableListOf<ARHapticEvent>()
        state.gestureHapticSink = { events += it }
        updateInstantPlacement(placement, listOf(plane), state, true)
        assertEquals(TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE, state.trackingMethod)
        assertEquals(0, plane.anchorsCreated)
        point.method = TrackingMethod.FULL_TRACKING
        repeat(3) { updateInstantPlacement(placement, listOf(plane), state, true) }
        assertEquals(TrackingMethod.FULL_TRACKING, state.trackingMethod)
        assertSame(plane, placement.planeOrNull)
        assertTrue(old.detached)
        assertEquals(1, plane.anchorsCreated)
        assertEquals(1, state.placementsCreated)
        assertEquals(listOf(ARHapticEvent.Placed), events)
        assertArrayEquals(old.pose.rotationQuaternion, placement.pose.rotationQuaternion, 0.00001f)
    }

    @Test fun `landing waits for gestures tracking and a containing floor at the refined point`() {
        val (point, placement, state) = standing()
        point.method = TrackingMethod.FULL_TRACKING
        val plane = TestPlane()
        state.beginAdjustment()
        updateInstantPlacement(placement, listOf(plane), state, true)
        assertEquals(0, plane.anchorsCreated)
        state.endAdjustment()
        updateInstantPlacement(placement, listOf(plane), state, false)
        assertEquals(0, plane.anchorsCreated)
        plane.contains = false
        updateInstantPlacement(placement, listOf(plane), state, true)
        assertEquals(0, plane.anchorsCreated)
        val wrongHeight = TestPlane(Pose.makeTranslation(0f, 0f, 0f))
        updateInstantPlacement(placement, listOf(wrongHeight), state, true)
        assertEquals(0, wrongHeight.anchorsCreated)
        plane.contains = true
        updateInstantPlacement(placement, listOf(plane), state, true)
        assertSame(plane, placement.planeOrNull)
    }

    @Test fun `failed landing retains the live anchor and retries without a haptic`() {
        val (point, placement, state) = standing()
        point.method = TrackingMethod.FULL_TRACKING
        val old = placement.anchor as FakePlacementAnchor
        val plane = TestPlane().apply { anchorState = TrackingState.PAUSED }
        val events = mutableListOf<ARHapticEvent>()
        state.gestureHapticSink = { events += it }
        updateInstantPlacement(placement, listOf(plane), state, true)
        assertSame(old, placement.anchor)
        assertFalse(old.detached)
        assertTrue(plane.lastAnchor!!.detached)
        assertTrue(events.isEmpty())
        plane.anchorState = TrackingState.TRACKING
        updateInstantPlacement(placement, listOf(plane), state, true)
        assertTrue(old.detached)
        assertEquals(listOf(ARHapticEvent.Placed), events)
    }

    @Test fun `full tracking never downgrades on loss and no later plane can move a landed model`() {
        val (point, placement, state) = standing()
        point.method = TrackingMethod.FULL_TRACKING
        val plane = TestPlane()
        updateInstantPlacement(placement, listOf(plane), state, true)
        val landed = placement.anchor
        state.onTrackingMethod(TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE)
        updateInstantPlacement(placement, listOf(TestPlane()), state, false)
        assertEquals(TrackingMethod.FULL_TRACKING, state.trackingMethod)
        assertSame(landed, placement.anchor)
    }

    @Test fun `the pre instant placement Compose binary descriptor still exists`() {
        val scene = Class.forName("io.github.sceneview.ar.AutoPlacementSceneKt")
        val expected = listOf(
            "boolean", "androidx.compose.ui.Modifier", "io.github.sceneview.ar.AutoPlacementState",
            "io.github.sceneview.ar.PlacementSurface", "com.google.android.filament.Engine",
            "io.github.sceneview.loaders.ModelLoader", "io.github.sceneview.loaders.MaterialLoader",
            "boolean", "boolean", "androidx.compose.foundation.layout.PaddingValues", "java.io.File",
            "kotlin.jvm.functions.Function1", "kotlin.jvm.functions.Function1",
            "kotlin.jvm.functions.Function1", "kotlin.jvm.functions.Function1",
            "kotlin.jvm.functions.Function4", "androidx.compose.runtime.Composer", "int", "int", "int",
        )
        assertTrue("published callers must still link to the original descriptor", scene.declaredMethods.any {
            it.name == "AutoPlacementScene" && it.parameterTypes.map { type -> type.name } == expected
        })
    }

    private fun standing(): Triple<FakeInstantPlacementPoint, AutoPlacementResult, AutoPlacementState> {
        val point = FakeInstantPlacementPoint()
        // Give the anchor an offset and yaw relative to the point, as a user's move/twist would.
        val pose = Pose(floatArrayOf(0.2f, -1.3f, -1.4f), floatArrayOf(0f, 0.4f, 0f, 0.916515f))
        val placement = AutoPlacementCandidate(point, pose, null).createAnchor()!!
        val state = AutoPlacementState().apply {
            requestPlacement()
            onFrame(FrameInput(600, true, true))
        }
        updateInstantPlacement(placement, emptyList(), state, true, initial = true)
        return Triple(point, placement, state)
    }

    /** Compile-only regressions: never create Filament or an ARCore session on the JVM. */
    @Composable
    private fun sourceCompatibleCalls(
        state: AutoPlacementState,
        engine: Engine,
        modelLoader: ModelLoader,
        materialLoader: MaterialLoader,
        content: @Composable ARSceneScope.(AutoPlacementResult) -> Unit,
    ) {
        AutoPlacementScene(true, Modifier, state, PlacementSurface.SURFACE, engine,
            modelLoader, materialLoader, true, true, PaddingValues(0.dp), null,
            null, null, null, null, content)
        AutoPlacementScene(assetReady = true) { result -> content(result) }
        AutoPlacementScene(assetReady = true, content = content, instantPlacement = true)
        AutoPlacementScene(true, Modifier, state, PlacementSurface.SURFACE, engine,
            modelLoader, materialLoader, true, true, PaddingValues(0.dp), null,
            null, null, null, null, content, true, 2f)
    }

    private fun find(
        frame: TestFrame,
        enabled: Boolean = true,
        due: Boolean = true,
        surface: PlacementSurface = PlacementSurface.SURFACE,
        planes: List<Plane> = emptyList(),
    ) = findAutoPlacementCandidate(frame, planes, 100, 100, surface, enabled, due, 0.3f)

    private class TestPlane(private val location: Pose = Pose.makeTranslation(0f, -1.3f, -1.14f)) : Plane() {
        var contains = true
        var anchorsCreated = 0
        var anchorState = TrackingState.TRACKING
        var lastAnchor: FakePlacementAnchor? = null
        override fun getType() = Type.HORIZONTAL_UPWARD_FACING
        override fun getTrackingState() = TrackingState.TRACKING
        override fun getSubsumedBy(): Plane? = null
        override fun getCenterPose() = location
        override fun isPoseInPolygon(pose: Pose) = contains
        override fun createAnchor(pose: Pose): Anchor {
            anchorsCreated++
            return FakePlacementAnchor(pose).apply { state = anchorState; lastAnchor = this }
        }
    }

    private class TestHit(private val surface: Trackable, private val location: Pose) : HitResult() {
        override fun getTrackable() = surface
        override fun getHitPose() = location
        override fun getDistance() = 1.7f
    }

    private class TestFrame : Frame() {
        var cameraState = TrackingState.TRACKING
        var hits: List<HitResult> = emptyList()
        var instantResults: List<HitResult> = FakeInstantPlacementPoint().let { listOf(TestHit(it, it.pose)) }
        var instantHits = 0
        var distance = 0f
        private val testCamera = object : Camera() {
            override fun getTrackingState() = cameraState
            override fun getPose() = Pose.IDENTITY
            override fun getDisplayOrientedPose() = Pose(floatArrayOf(0f, 0f, 0f),
                floatArrayOf(-0.3826834f, 0f, 0f, 0.9238795f))
            override fun getViewMatrix(dest: FloatArray, offset: Int) { Pose.IDENTITY.toMatrix(dest, offset) }
            override fun getProjectionMatrix(dest: FloatArray, offset: Int, near: Float, far: Float) {
                Pose.IDENTITY.toMatrix(dest, offset)
                dest[offset] = 0.25f
                dest[offset + 5] = 0.25f
            }
        }
        override fun getCamera() = testCamera
        override fun hitTest(x: Float, y: Float) = hits
        override fun hitTestInstantPlacement(x: Float, y: Float, approximateDistance: Float): List<HitResult> {
            instantHits++
            distance = approximateDistance
            return instantResults
        }
    }
}
