package io.github.sceneview.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [PreparePlayGate] — the deferred-play decision [SpatialAudioPlayer]
 * uses to avoid calling `MediaPlayer.start()` before `prepareAsync()`'s `onPrepared`
 * callback fires (#3427). `SpatialAudioPlayer` itself is JNI-bound and deliberately not unit
 * tested (see [SpatialAudioNodeTest]'s KDoc) — this class is the one bit of its lifecycle
 * that has no `MediaPlayer` dependency, so it is tested directly instead.
 *
 * @see SpatialAudioNodeTest
 */
class PreparePlayGateTest {

    @Test
    fun `play requested after prepared starts immediately`() {
        val gate = PreparePlayGate()
        gate.markPrepared()
        assertTrue("already prepared — play() should start now", gate.requestPlay())
    }

    @Test
    fun `play requested before prepared is deferred`() {
        val gate = PreparePlayGate()
        assertFalse("not prepared yet — play() must wait", gate.requestPlay())
        assertFalse(gate.isPrepared)
    }

    @Test
    fun `markPrepared honours a play requested earlier`() {
        val gate = PreparePlayGate()
        gate.requestPlay()
        assertTrue("a pending play() must be honoured once prepared", gate.markPrepared())
        assertTrue(gate.isPrepared)
    }

    @Test
    fun `markPrepared without a prior play request does not start playback`() {
        val gate = PreparePlayGate()
        assertFalse(gate.markPrepared())
    }

    @Test
    fun `markPrepared only honours a pending play once`() {
        val gate = PreparePlayGate()
        gate.requestPlay()
        assertTrue(gate.markPrepared())
        // A second onPrepared callback (should not happen in practice, but the gate must
        // not replay a stale request) must not re-trigger playback.
        assertFalse(gate.markPrepared())
    }

    @Test
    fun `cancelPendingPlay clears a deferred play request`() {
        val gate = PreparePlayGate()
        gate.requestPlay()
        gate.cancelPendingPlay()
        assertFalse(
            "pause()/stop() before prepared must cancel the deferred play",
            gate.markPrepared()
        )
    }

    @Test
    fun `cancelPendingPlay does not affect the prepared flag`() {
        val gate = PreparePlayGate()
        gate.markPrepared()
        gate.cancelPendingPlay()
        assertTrue(gate.isPrepared)
    }

    @Test
    fun `play requested twice before prepared still starts once prepared`() {
        val gate = PreparePlayGate()
        gate.requestPlay()
        gate.requestPlay()
        assertTrue(gate.markPrepared())
    }
}
