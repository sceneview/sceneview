package io.github.sceneview.demo.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #4618: dictation chains the system dialog through pauses and never loses what it heard. */
class DictationSessionTest {

    private class FakeLauncher : SpeechDialogLauncher {
        var launches = 0
        var opens = true
        override fun launch(): Boolean {
            launches++
            return opens
        }
    }

    private val launcher = FakeLauncher()
    private val updates = mutableListOf<DictationState>()
    private val session = DictationSession(launcher = launcher, onUpdate = { updates += it })

    @Test
    fun startOpensTheDialogWithAnEmptyTranscript() {
        session.start()
        assertTrue(session.listening)
        assertEquals(1, launcher.launches)
        assertEquals(DictationState(listening = true, transcript = ""), updates.last())
    }

    @Test
    fun startingTwiceOpensOneDialog() {
        session.start()
        session.start()
        assertEquals(1, launcher.launches)
    }

    @Test
    fun aRoundWithTextIsAppendedAndTheDialogReopens() {
        session.start()
        session.onRoundResult("the model is too dark", canceled = false)
        assertEquals(2, launcher.launches)
        assertTrue(session.listening)
        session.onRoundResult("on the floor", canceled = false)
        assertEquals(3, launcher.launches)
        assertEquals(
            DictationState(listening = true, transcript = "the model is too dark on the floor"),
            updates.last(),
        )
    }

    @Test
    fun cancelingTheDialogEndsTheChainAndKeepsTheText() {
        session.start()
        session.onRoundResult("first", canceled = false)
        session.onRoundResult(null, canceled = true)
        assertFalse(session.listening)
        assertEquals(2, launcher.launches)
        assertEquals(DictationState(listening = false, transcript = "first"), updates.last())
    }

    @Test
    fun anEmptyRoundEndsTheChain() {
        session.start()
        session.onRoundResult("said something", canceled = false)
        session.onRoundResult("  ", canceled = false)
        assertFalse(session.listening)
        assertEquals(2, launcher.launches)
        assertEquals("said something", session.transcript)
    }

    @Test
    fun aRoundWithNoResultEndsTheChain() {
        session.start()
        session.onRoundResult(null, canceled = false)
        assertFalse(session.listening)
        assertEquals(1, launcher.launches)
    }

    @Test
    fun userStopEndsTheChainAndKeepsTheText() {
        session.start()
        session.onRoundResult("tap stop", canceled = false)
        session.stop()
        assertFalse(session.listening)
        assertEquals(DictationState(listening = false, transcript = "tap stop"), updates.last())
    }

    @Test
    fun nothingReopensAfterStop() {
        session.start()
        session.stop()
        session.onRoundResult("late", canceled = false)
        assertEquals(1, launcher.launches)
        assertFalse(session.listening)
        assertEquals("", session.transcript)
    }

    @Test
    fun aDialogThatCannotOpenEndsTheChain() {
        session.start()
        launcher.opens = false
        session.onRoundResult("kept", canceled = false)
        assertFalse(session.listening)
        assertEquals("kept", session.transcript)
    }

    @Test
    fun aDialogThatCannotOpenAtStartLeavesItIdle() {
        launcher.opens = false
        session.start()
        assertFalse(session.listening)
        assertFalse(updates.last().listening)
    }

    @Test
    fun startingAgainBeginsAFreshTranscript() {
        session.start()
        session.onRoundResult("old", canceled = false)
        session.stop()
        session.start()
        assertEquals("", session.transcript)
        assertTrue(session.listening)
    }

    @Test
    fun appendDictationJoinsAfterTheExistingText() {
        assertEquals("heard", appendDictation("", "heard"))
        assertEquals("typed heard", appendDictation("typed  ", "heard"))
    }
}
