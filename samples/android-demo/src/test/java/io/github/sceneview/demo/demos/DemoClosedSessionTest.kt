package io.github.sceneview.demo.demos

import io.github.sceneview.ar.ARSessionFailure
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import io.github.sceneview.demo.demos.internal.*
import io.github.sceneview.math.Position
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** A freed native handle must never be queried, including while the retry card is up. */
private class SessionHandle {
    var closed = false
    fun read() { check(!closed) { "Read from a closed ARCore session" } }
}

private fun <S : DemoSessionState> assertSessionLifecycle(
    state: S,
    populate: S.(SessionHandle) -> Unit,
    references: S.() -> List<SessionHandle>,
) {
    val old = SessionHandle()
    state.populate(old)
    val live = state.references()
    assertTrue(live.isNotEmpty())
    live.forEach { it.read() }
    // Resume failures keep the session and all its placements.
    assertFalse(state.onFailure(fatal = false))
    assertEquals(live, state.references())
    old.closed = true
    assertTrue(state.onFailure(fatal = true))
    // Simulate both a tap under the failure card and the first update after retry.
    state.references().forEach { it.read() }
    assertTrue(state.references().isEmpty())
    val retry = SessionHandle()
    state.populate(retry)
    state.references().forEach { it.read() }
    assertEquals(live.size, state.references().size)
    assertTrue(state.references().all { it === retry })
}

class DemoClosedSessionARImageTest {
    @Test fun `new session never reads images detected by the closed session`() {
        val state = ImageSessionState<SessionHandle>()
        val generation = state.generation
        assertSessionLifecycle(state, { detectedImages += it }, { detectedImages.toList() })
        assertNotEquals(generation, state.generation)
        assertWired("ARImageDemo", "ImageSessionState")
    }
}

class DemoClosedSessionOrbitalARTest {
    @Test fun `new session creates a new world anchor before projecting planets`() {
        assertSessionLifecycle(OrbitalSessionState<SessionHandle>(),
            { userAnchor = it }, { listOfNotNull(userAnchor) })
        assertWired("OrbitalARDemo", "OrbitalSessionState")
    }
}

class DemoClosedSessionARDepthOfFieldTest {
    @Test fun `tap under failure card cannot read the old depth frame`() {
        assertSessionLifecycle(PortraitSessionState<SessionHandle>(),
            { latestFrame = it }, { listOfNotNull(latestFrame) })
        assertWired("ARDepthOfFieldDemo", "PortraitSessionState")
    }
}

class DemoClosedSessionARSurfacesTest {
    @Test fun `fatal close drops sofa and frame but resume failure preserves both`() {
        assertSessionLifecycle(SurfaceSessionState<SessionHandle, SessionHandle, SessionHandle>(),
            { latestFrame = it; anchorNode = it; placedAnchor = it },
            { listOfNotNull(latestFrame, anchorNode, placedAnchor) })
        assertWired("ARSurfacesDemo", "SurfaceSessionState")
    }
}

class DemoClosedSessionARCloudAnchorTest {
    @Test fun `closed session cannot be hosted resolved reset or cleaned up by a late result`() {
        val state = CloudAnchorSessionState<SessionHandle, SessionHandle, SessionHandle, SessionHandle, SessionHandle>()
        val generation = state.requests.current
        assertSessionLifecycle(state,
            { arSession = it; localPlacement = it; resolvedAnchor = it
                hostAnchor = it; hostFuture = it; resolveFuture = it },
            { listOfNotNull(arSession, localPlacement, resolvedAnchor, hostAnchor, hostFuture, resolveFuture) })
        assertFalse(state.requests.accepts(generation))
        assertFalse(state.requests.canRelease(generation))
        assertTrue(state.requests.canRelease(state.requests.current))
        assertWired("ARCloudAnchorDemo", "CloudAnchorSessionState")
    }
}

class DemoClosedSessionARGeospatialTest {
    @Test fun `drop clear and retry cannot reach old session or resolved anchors`() {
        val state = GeospatialSessionState<SessionHandle, SessionHandle, SessionHandle>()
        val generation = state.requests.current
        assertSessionLifecycle(state,
            { arSession = it; cameraPose = it; drops += it },
            { listOfNotNull(arSession, cameraPose) + drops })
        assertFalse(state.requests.accepts(generation))
        assertFalse(state.requests.canRelease(generation))
        assertWired("ARGeospatialAnchorsDemo", "GeospatialSessionState")
        // Ordinary Clear invalidates drop callbacks, but must still deliver the VPS verdict.
        assertTrue(demoSource("ARGeospatialAnchorsDemo").contains(
            "continuation.isActive && requests.canRelease(generation)"))
    }
}

class DemoClosedSessionARRecordPlaybackTest {
    @Test fun `tap recording and playback polling cannot reach the closed take`() {
        assertSessionLifecycle(RecordingSessionState<SessionHandle, SessionHandle, SessionHandle>(),
            { arSession = it; latestFrame = it; anchors += it },
            { listOfNotNull(arSession, latestFrame) + anchors })
        val source = demoSource("ARRecordPlaybackDemo")
        assertTrue(source.contains("onSessionFailure = { take.onSessionFailure() }"))
        assertTrue(source.contains("sessionState.onFailure(fatal = true)"))
        val failureHandler = source.substringAfter("fun onSessionFailure()")
        assertTrue(failureHandler.indexOf("recorder.detach()") < failureHandler.indexOf("recorder.stop()"))
    }
}

class DemoClosedSessionPointAndAskTest {
    @Test fun `tap layout reset and late answers cannot reuse closed panels or props`() {
        val state = AskSessionState<SessionHandle, SessionHandle, SessionHandle>()
        val generation = state.generation
        assertSessionLifecycle(state,
            { latestFrame = it; pendingPanel = it; panels += it; placedProps += it },
            { listOfNotNull(latestFrame, pendingPanel) + panels + placedProps })
        assertNotEquals(generation, state.generation)
        assertWired("PointAndAskDemo", "AskSessionState")
    }
}

class DemoClosedSessionARMeasureTest {
    @Test fun `undo clear share and leaving cannot reach the closed session's anchors`() {
        assertSessionLifecycle(MeasureSessionState<SessionHandle, SessionHandle>(),
            { handle ->
                session = handle
                board = MeasureBoard<SessionHandle>().tap(handle, closesOutline = false)
                objectMeasure = ObjectMeasure<SessionHandle>().start().capture(handle) { Position() }
                positions = mapOf(handle to Position())
                objectPositions = mapOf(handle to Position())
            },
            { listOfNotNull(session) + board.allPoints + objectMeasure.endpoints +
                positions.keys + objectPositions.keys })
        assertWired("ARMeasureDemo", "MeasureSessionState")
    }

    @Test fun `a failed session's board keeps its mode but no anchor to detach`() {
        val state = MeasureSessionState<SessionHandle, SessionHandle>()
        state.board = MeasureBoard<SessionHandle>(mode = MeasureMode.Room).tap(SessionHandle(), closesOutline = false)
        assertTrue(state.onFailure(fatal = true))
        assertEquals(MeasureMode.Room, state.board.mode)
        assertTrue(state.board.allPoints.isEmpty())
        // Leaving the screen detaches what the holder still has, read at dispose, not a stale copy.
        val source = demoSource("ARMeasureDemo")
        assertTrue(source.contains("onDispose { sessionState.board.allPoints.distinct().forEach { it.detach() } }"))
        assertTrue(source.contains("onDispose { sessionState.objectMeasure.endpoints.distinct().forEach { it.detach() } }"))
        assertFalse(source.contains("latestBoard"))
    }
}

class DemoClosedSessionScanTest {
    @Test fun `auto start after retry cannot start a capture on the closed session's frame`() {
        assertSessionLifecycle(ScanSessionState<SessionHandle>(),
            { latestFrame = it }, { listOfNotNull(latestFrame) })
        assertWired("ARRerunDemo", "ScanSessionState")
        val failure = demoSource("ARRerunDemo").substringAfter("onSessionFailure = { failure ->")
            .substringBefore("},")
        assertTrue(failure.contains("isTracking = false"))
        assertTrue(failure.contains("if (sessionState.onFailure(failure)) debugRecorder.forgetSession()"))
    }

    @Test fun `the scan recorder forgets the closed session's planes but keeps their ids unique`() {
        val source = demoSource("ArDebugView").substringAfter("fun forgetSession()").substringBefore("}")
        listOf("planeIds.clear()", "livePlanes.clear()", "anchorIds.clear()").forEach {
            assertTrue(it, source.contains(it))
        }
        assertFalse(source.contains("lastPlaneId"))
        assertFalse(demoSource("ArDebugView").contains("planeIds.size + 1"))
    }
}

class DemoClosedSessionFingerDrawingTest {
    @Test fun `a new session never reads the closed session's stroke anchors`() {
        assertSessionLifecycle(DrawingSessionState<SessionHandle>(),
            { handle ->
                anchorOf = mapOf(0 to handle, 1 to handle)
                healthOf = mapOf(handle to AnchorHealth.Tracking)
            },
            { anchorOf.values.distinct() + healthOf.keys })
        assertWired("FingerDrawingArDemo", "DrawingSessionState")
    }

    @Test fun `strokes leave with their anchors but stroke ids keep counting`() {
        val state = DrawingSessionState<SessionHandle>()
        state.drawing = Drawing()
            .penDown(Position(), radius = 0.01f, colorIndex = 0, spacing = 0.01f)
            .penUp()
        val nextId = state.drawing.nextId
        state.anchorOf = mapOf(0 to SessionHandle())
        assertFalse(state.onFailure(fatal = false))
        assertFalse(state.drawing.isEmpty)
        assertTrue(state.onFailure(fatal = true))
        assertTrue(state.drawing.isEmpty)
        assertEquals(nextId, state.drawing.nextId)
        // The pen is not Compose state: the failure handler must drop its anchor too.
        val failure = demoSource("FingerDrawingArDemo").substringAfter("onSessionFailure = { failure ->")
            .substringBefore("},")
        assertTrue(failure.contains("pen.anchor = null"))
        listOf("pen.steady = null", "pen.pendingDown = false", "pen.pendingUp = false", "isTracking = false")
            .forEach { assertTrue(it, failure.contains(it)) }
        assertFalse("the init scrim would cover the retry card", failure.contains("cameraReady = false"))
    }
}

class DemoClosedSessionStreetscapeTest {
    @Test fun `the scene and the coverage check never read the closed session's geometry`() {
        assertSessionLifecycle(StreetscapeSessionState<SessionHandle, SessionHandle>(),
            { session = it; geometries += it }, { listOfNotNull(session) + geometries })
        assertWired("ARSceneGeometryDemo", "StreetscapeSessionState")
        val source = demoSource("ARSceneGeometryDemo")
        assertTrue(source.contains("onSessionFailure = { failure -> state.onSessionFailure(failure) }"))
        assertTrue(source.contains("var session by sessionState::session"))
        val failure = source.substringAfter("fun onSessionFailure(failure: ARSessionFailure)").substringBefore("\n    }")
        listOf("geometryCount = 0", "isTracking = false", "earthTracking = false")
            .forEach { assertTrue(it, failure.contains(it)) }
        // Closing the request generation keeps the cancelled coverage check off the closed future.
        assertTrue(source.contains("session.awaitDemoVpsAvailability("))
        assertFalse(source.contains("session.awaitVpsAvailability("))
        assertTrue(demoSource("internal/DemoSessionState").substringAfter("class StreetscapeSessionState")
            .substringBefore("session = null").contains("requests.closeSession()"))
    }
}

class DemoClosedSessionFatalFailureClassificationTest {
    @Test fun `typed fatal failure invalidates session references`() {
        val state = PortraitSessionState<Any>().apply { latestFrame = Any() }
        assertTrue(state.onFailure(ARSessionFailure.Fatal(FatalException())))
        assertNull(state.latestFrame)
    }

    @Test fun `typed camera resume failure preserves session references`() {
        val frame = Any()
        val state = PortraitSessionState<Any>().apply { latestFrame = frame }
        assertFalse(state.onFailure(ARSessionFailure.CameraNotAvailable(CameraNotAvailableException())))
        assertSame(frame, state.latestFrame)
    }

    @Test fun `reset results can be released until their session closes`() {
        val requests = CloudRequestGeneration()
        val first = requests.current
        requests.invalidate()
        assertFalse(requests.accepts(first))
        assertTrue(requests.canRelease(first))
        requests.closeSession()
        assertFalse(requests.canRelease(first))
        assertFalse(requests.canRelease(requests.current - 1))
        assertTrue(requests.accepts(requests.current))
        assertTrue(requests.canRelease(requests.current))
    }
}

class DemoClosedSessionWiringTest {
    @Test fun `only demos already using the scaffold failure card replace their scene`() {
        // Pinned to main before #5196: adding a demo here removes the SDK's retry card.
        val expected = setOf(
            "ARBodyTrackerDemo", "ARDepthOfFieldDemo", "ARImageDemo", "ARMLObjectLabelDemo",
            "ARMeasureDemo", "ARPlaneNodeDemo", "ARPointCloudDemo", "ARRecordPlaybackDemo",
            "ARSceneSemanticsDemo", "ARSurfacesDemo", "OrbitalARDemo",
        )
        val scaffoldCall = Regex("""DemoScaffold\((.*?)\n[ \t]*\) \{""", RegexOption.DOT_MATCHES_ALL)
        val failureArgument = Regex("""(?m)^\s*arSessionFailed\s*=""")
        val actual = File("src/main/java/io/github/sceneview/demo/demos").listFiles().orEmpty()
            .filter { it.extension == "kt" }
            .filter { file -> scaffoldCall.findAll(file.readText()).any { call ->
                failureArgument.containsMatchIn(call.groupValues[1])
            } }
            .map { it.nameWithoutExtension }.toSet()
        assertEquals(expected, actual)
    }

    @Test fun `scaffold closes also clear references on nonfatal failures`() {
        scaffoldClosingDemos.forEach { name ->
            val source = demoSource(name)
            assertTrue(name, source.contains("sessionState.onFailure(fatal = true)"))
            assertFalse(name, source.contains("sessionState.onFailure(failure)"))
        }
    }

    @Test fun `failure flags survive resume after a rejected playback dataset`() {
        val flags = mapOf(
            "ARSurfacesDemo" to "arSessionFailed", "ARGeospatialAnchorsDemo" to "sessionError",
            "PointAndAskDemo" to "arSessionFailed",
            "ARSceneGeometryDemo" to "state.sessionError",
        )
        flags.forEach { (name, flag) ->
            val source = demoSource(name)
            val resumed = source.substringAfter("onSessionResumed =", "").substringBefore("},")
            assertFalse(name, resumed.contains("$flag ="))
            val availability = source.substringAfter("onARCoreAvailability =").substringBefore("},")
            assertTrue(name, availability.contains("if (it == null) $flag ="))
        }
        scaffoldClosingDemos.forEach { name ->
            assertFalse(name, demoSource(name).contains("onSessionResumed ="))
        }
    }

    @Test fun `feature comparison drops the stabilization session on fatal failure`() {
        val source = File("src/main/java/io/github/sceneview/demo/common/placement/ARFeatureComparison.kt")
            .readText()
        assertTrue(Regex("""onSessionFailure = \{ failure ->\s*if \(failure is ARSessionFailure.Fatal\) session = null\s*},""")
            .containsMatchIn(source))
    }

    @Test fun `VPS callback reads request generations on the main thread`() {
        val source = demoSource("ARGeospatialAnchorsDemo")
        val callback = source.substringAfter("checkVpsAvailabilityAsync(latitude, longitude) { result ->")
            .substringBefore("continuation.invokeOnCancellation")
        assertTrue(Regex("""mainHandler.post \{\s*if \(continuation.isActive && requests.canRelease\(generation\)\)""")
            .containsMatchIn(callback))
    }
}

private val scaffoldClosingDemos = setOf(
    "ARImageDemo", "ARDepthOfFieldDemo", "OrbitalARDemo", "ARRecordPlaybackDemo", "ARMeasureDemo",
)

private fun demoSource(name: String) =
    File("src/main/java/io/github/sceneview/demo/demos/$name.kt").readText()

/** Complement behavioral tests with callback wiring, since composables need native ARCore. */
private fun assertWired(name: String, holder: String) {
    val source = demoSource(name)
    assertTrue(source.contains(holder))
    assertTrue(source.contains("onSessionFailure = {"))
    val clearCall = if (name in scaffoldClosingDemos) "sessionState.onFailure(fatal = true)"
        else "sessionState.onFailure(failure)"
    assertTrue(source.contains(clearCall))
}
