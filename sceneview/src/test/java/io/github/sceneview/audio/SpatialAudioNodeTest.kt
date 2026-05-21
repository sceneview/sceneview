package io.github.sceneview.audio

import io.github.sceneview.math.Position
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM smoke tests for [SpatialAudioEngine], [panLR], [distance3] and
 * [rightFromForwardUp]. Robolectric is deliberately avoided so the suite runs in
 * milliseconds in the regular `:sceneview:testDebugUnitTest` task without an emulator
 * stub.
 *
 * The full `SpatialAudioNode` composable path (per-node `MediaPlayer` prepare / start /
 * stop) is exercised end-to-end by the on-device QA harness
 * (`.maestro/android/spatial-audio.yaml`) because `MediaPlayer` is JNI-bound and not safe
 * to mock with vanilla mockito. The engine registry logic itself *is* covered here, via a
 * fake [AudioListenerTarget] that records the poses pushed to it.
 */
class SpatialAudioNodeTest {

    @After
    fun tearDown() {
        SpatialAudioEngine.resetForTest()
    }

    // ── distance3 / panLR ─────────────────────────────────────────────────────────────

    @Test fun distance3CommonCases() {
        val origin = Position(x = 0f)
        assertEquals(0f, distance3(origin, origin), 1e-6f)
        assertEquals(1f, distance3(origin, Position(x = 1f)), 1e-6f)
        assertEquals(5f, distance3(origin, Position(x = 3f, y = 4f)), 1e-6f)
        assertEquals(
            7.071f,
            distance3(origin, Position(x = 5f, y = 5f, z = 0f)),
            1e-3f
        )
    }

    @Test fun panLRCenteredWhenSourceOnListener() {
        val listener = Position(x = 0f)
        val right = Position(x = 1f)
        val (l, r) = panLR(listener, listener, right)
        // Both channels equal energy.
        assertEquals(l, r, 1e-4f)
    }

    @Test fun panLRFullRightWhenSourceIsOnRightAxis() {
        val listener = Position(x = 0f)
        val right = Position(x = 1f)
        val source = Position(x = 5f)  // along +x, fully right
        val (l, r) = panLR(source, listener, right)
        // Right channel dominates.
        assertTrue("right > left when source is on right", r > l)
        // Equal-power: l^2 + r^2 ≈ 1
        assertEquals(1f, l * l + r * r, 1e-3f)
    }

    @Test fun panLRFullLeftWhenSourceIsOnLeftAxis() {
        val listener = Position(x = 0f)
        val right = Position(x = 1f)
        val source = Position(x = -5f)  // along -x, fully left
        val (l, r) = panLR(source, listener, right)
        assertTrue("left > right when source is on left", l > r)
        assertEquals(1f, l * l + r * r, 1e-3f)
    }

    // ── rightFromForwardUp ────────────────────────────────────────────────────────────

    @Test fun rightFromForwardUpStandardCameraBasis() {
        // Camera looking down -Z with +Y up → right = +X.
        val right = rightFromForwardUp(Position(z = -1f), Position(y = 1f))
        assertEquals(1f, right.x, 1e-4f)
        assertEquals(0f, right.y, 1e-4f)
        assertEquals(0f, right.z, 1e-4f)
    }

    @Test fun rightFromForwardUpToleratesParallelInput() {
        // forward parallel to up → degenerate cross product → safe +X fallback.
        val right = rightFromForwardUp(Position(y = 1f), Position(y = 2f))
        assertEquals(1f, right.x, 1e-4f)
        assertEquals(0f, right.y, 1e-4f)
        assertEquals(0f, right.z, 1e-4f)
    }

    @Test fun setListenerPoseDerivesRightFromForwardAndUp() {
        // setSpatialAudioListenerPose takes (position, forward, up); the engine must end
        // up with a right vector derived via forward × up.
        val recorder = RecordingTarget()
        SpatialAudioEngine.register(recorder)
        setSpatialAudioListenerPose(
            position = Position(x = 0f),
            forward = Position(z = -1f),
            up = Position(y = 1f),
        )
        val right = recorder.lastRight!!
        assertEquals(1f, right.x, 1e-4f)
        assertEquals(0f, right.y, 1e-4f)
        assertEquals(0f, right.z, 1e-4f)
    }

    // ── SpatialAudioEngine lifecycle ──────────────────────────────────────────────────

    @Test fun registerPushesCurrentListenerImmediately() {
        SpatialAudioEngine.setListenerPose(
            position = Position(x = 7f),
            forward = Position(z = -1f),
            right = Position(x = 1f),
        )
        val recorder = RecordingTarget()
        SpatialAudioEngine.register(recorder)
        // Registering must push the *current* pose so the player isn't at full volume
        // for its first frame.
        assertEquals(1, recorder.updateCount)
        assertEquals(7f, recorder.lastPosition!!.x, 1e-4f)
    }

    @Test fun refreshAllUpdatesEveryRegisteredPlayer() {
        val a = RecordingTarget()
        val b = RecordingTarget()
        SpatialAudioEngine.register(a)   // 1 update each from register's initial push
        SpatialAudioEngine.register(b)
        val baseA = a.updateCount
        val baseB = b.updateCount
        SpatialAudioEngine.refreshAll()
        assertEquals("refreshAll updates player A", baseA + 1, a.updateCount)
        assertEquals("refreshAll updates player B", baseB + 1, b.updateCount)
        assertEquals(2, SpatialAudioEngine.registeredPlayerCount)
    }

    @Test fun setListenerPoseUpdatesEveryRegisteredPlayer() {
        val a = RecordingTarget()
        val b = RecordingTarget()
        SpatialAudioEngine.register(a)
        SpatialAudioEngine.register(b)
        SpatialAudioEngine.setListenerPose(
            position = Position(x = 3f),
            forward = Position(z = -1f),
            right = Position(x = 1f),
        )
        assertEquals(3f, a.lastPosition!!.x, 1e-4f)
        assertEquals(3f, b.lastPosition!!.x, 1e-4f)
    }

    @Test fun refreshPlayerUpdatesOnlyThatPlayer() {
        val a = RecordingTarget()
        val b = RecordingTarget()
        SpatialAudioEngine.register(a)
        SpatialAudioEngine.register(b)
        val baseB = b.updateCount
        SpatialAudioEngine.refreshPlayer(a)
        // b must NOT be touched — a single source moving only refreshes itself.
        assertEquals("refreshPlayer leaves other players untouched", baseB, b.updateCount)
    }

    @Test fun unregisterRemovesPlayerFromRegistry() {
        val a = RecordingTarget()
        val b = RecordingTarget()
        SpatialAudioEngine.register(a)
        SpatialAudioEngine.register(b)
        assertEquals(2, SpatialAudioEngine.registeredPlayerCount)
        SpatialAudioEngine.unregister(a)
        assertEquals(1, SpatialAudioEngine.registeredPlayerCount)
        // A subsequent refresh must not reach the unregistered player.
        val baseA = a.updateCount
        SpatialAudioEngine.refreshAll()
        assertEquals("unregistered player no longer refreshed", baseA, a.updateCount)
    }

    @Test fun registerIsIdempotent() {
        val a = RecordingTarget()
        SpatialAudioEngine.register(a)
        SpatialAudioEngine.register(a)
        assertEquals("double register adds the player once", 1, SpatialAudioEngine.registeredPlayerCount)
    }

    @Test fun engineResetClearsListenerAndPlayers() {
        val a = RecordingTarget()
        SpatialAudioEngine.register(a)
        SpatialAudioEngine.setListenerPose(
            position = Position(x = 100f),
            forward = Position(z = -1f),
            right = Position(x = 1f),
        )
        SpatialAudioEngine.resetForTest()
        assertEquals(0, SpatialAudioEngine.registeredPlayerCount)
        // After reset, refreshAll is safe even with no players registered.
        SpatialAudioEngine.refreshAll()
    }

    // ── AudioListenerSource ───────────────────────────────────────────────────────────

    @Test fun audioListenerSourceCameraIsDefaultSingleton() {
        val camera: AudioListenerSource = AudioListenerSource.Camera
        assertNotNull(camera)
        assertEquals(AudioListenerSource.Camera, camera)
    }

    /** Records the listener poses pushed by the engine — a JNI-free [AudioListenerTarget]. */
    private class RecordingTarget : AudioListenerTarget {
        var updateCount = 0
        var lastPosition: Position? = null
        var lastForward: Position? = null
        var lastRight: Position? = null

        override fun updateFromListener(
            listenerPosition: Position,
            listenerForward: Position,
            listenerRight: Position
        ) {
            updateCount++
            lastPosition = listenerPosition
            lastForward = listenerForward
            lastRight = listenerRight
        }
    }
}
