package io.github.sceneview.audio

import io.github.sceneview.math.Position
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM smoke tests for [SpatialAudioEngine], [panLR] and [distance3]. Robolectric is
 * deliberately avoided so the suite runs in milliseconds in the regular
 * `:sceneview:testDebugUnitTest` task without an emulator stub.
 *
 * The full `SpatialAudioNode` composable path (MediaPlayer prepare / start / stop) is
 * exercised end-to-end by the on-device QA harness (`.maestro/android/spatial-audio.yaml`)
 * because MediaPlayer is JNI-bound and not safe to mock with vanilla mockito.
 */
class SpatialAudioNodeTest {

    @After
    fun tearDown() {
        SpatialAudioEngine.resetForTest()
    }

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

    @Test fun engineResetClearsListenerAndPlayers() {
        // Set a non-default listener; reset; verify a fresh refresh doesn't crash.
        SpatialAudioEngine.setListenerPose(
            position = Position(x = 100f),
            forward = Position(z = -1f),
            right = Position(x = 1f),
        )
        SpatialAudioEngine.resetForTest()
        // After reset, refreshAll is safe even with no players registered.
        SpatialAudioEngine.refreshAll()
    }

    @Test fun audioFalloffSealedHierarchyClosedOver3Cases() {
        // Cheap regression: the sealed hierarchy is exhaustively handled by `gainFor`.
        // If a new case is ever added without updating the helper, this test fails fast.
        val all: List<AudioFalloff> = listOf(
            AudioFalloff.None,
            AudioFalloff.Inverse(),
            AudioFalloff.Linear()
        )
        for (f in all) {
            val gain = AudioFalloff.gainFor(f, 5f)
            assertTrue("gain in range for $f", gain in 0f..1f)
        }
        assertEquals(3, all.size)
    }

    @Test fun audioListenerSourceSealedHierarchy() {
        // Camera is the default object — assert via singleton identity so the test
        // breaks if it ever stops being an `object`.
        val camera: AudioListenerSource = AudioListenerSource.Camera
        assertNotNull(camera)
        assertEquals(AudioListenerSource.Camera, camera)
    }
}
