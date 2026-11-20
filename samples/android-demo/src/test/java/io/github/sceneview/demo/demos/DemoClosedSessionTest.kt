package io.github.sceneview.demo.demos

import io.github.sceneview.ar.ARSessionFailure
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import io.github.sceneview.demo.demos.internal.*
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

class ARImageClosedSessionTest {
    @Test fun `retry never reads images detected by the closed session`() {
        val state = ImageSessionState<SessionHandle>()
        val generation = state.generation
        assertSessionLifecycle(state, { detectedImages += it }, { detectedImages.toList() })
        assertNotEquals(generation, state.generation)
        assertWired("ARImageDemo", "ImageSessionState")
    }
}

class OrbitalARClosedSessionTest {
    @Test fun `retry creates a new world anchor before projecting planets`() {
        assertSessionLifecycle(OrbitalSessionState<SessionHandle>(),
            { userAnchor = it }, { listOfNotNull(userAnchor) })
        assertWired("OrbitalARDemo", "OrbitalSessionState")
    }
}

class ARDepthOfFieldClosedSessionTest {
    @Test fun `tap under failure card cannot read the old depth frame`() {
        assertSessionLifecycle(PortraitSessionState<SessionHandle>(),
            { latestFrame = it }, { listOfNotNull(latestFrame) })
        assertWired("ARDepthOfFieldDemo", "PortraitSessionState")
    }
}

class ARSurfacesClosedSessionTest {
    @Test fun `fatal close drops sofa and frame but resume failure preserves both`() {
        assertSessionLifecycle(SurfaceSessionState<SessionHandle, SessionHandle, SessionHandle>(),
            { latestFrame = it; anchorNode = it; placedAnchor = it },
            { listOfNotNull(latestFrame, anchorNode, placedAnchor) })
        assertWired("ARSurfacesDemo", "SurfaceSessionState")
    }
}

class ARCloudAnchorClosedSessionTest {
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

class ARGeospatialClosedSessionTest {
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

class ARRecordPlaybackClosedSessionTest {
    @Test fun `tap recording and playback polling cannot reach the closed take`() {
        assertSessionLifecycle(RecordingSessionState<SessionHandle, SessionHandle, SessionHandle>(),
            { arSession = it; latestFrame = it; anchors += it },
            { listOfNotNull(arSession, latestFrame) + anchors })
        val source = demoSource("ARRecordPlaybackDemo")
        assertTrue(source.contains("onSessionFailure = { take.onSessionFailure(it) }"))
        assertTrue(source.contains("sessionState.onFailure(failure)"))
        val failureHandler = source.substringAfter("fun onSessionFailure(failure: ARSessionFailure)")
        assertTrue(failureHandler.indexOf("recorder.detach()") < failureHandler.indexOf("recorder.stop()"))
    }
}

class PointAndAskClosedSessionTest {
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

class FeaturePlacementClosedSessionTest {
    @Test fun `feature adapter forgets native planes and placement before retry`() {
        assertSessionLifecycle(FeaturePlacementSessionState<SessionHandle, SessionHandle>(),
            { placement = it; planes = listOf(it) }, { listOfNotNull(placement) + planes })
        val source = File("src/main/java/io/github/sceneview/demo/common/placement/FeaturePlacementScene.kt").readText()
        assertTrue(source.contains("sessionState.onFailure(failure)"))
        assertTrue(source.contains("state.resetPlacement(SystemClock.uptimeMillis())"))
    }
}

class DemoFatalFailureClassificationTest {
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

private fun demoSource(name: String) =
    File("src/main/java/io/github/sceneview/demo/demos/$name.kt").readText()

/** Complement behavioral tests with callback wiring, since composables need native ARCore. */
private fun assertWired(name: String, holder: String) {
    val source = demoSource(name)
    assertTrue(source.contains(holder))
    assertTrue(source.contains("onSessionFailure = { failure ->"))
    assertTrue(source.contains("sessionState.onFailure(failure)"))
}
