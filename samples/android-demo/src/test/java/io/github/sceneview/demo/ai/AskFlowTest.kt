package io.github.sceneview.demo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Point & Ask state machine (#3407).
 *
 * The regression that matters most is `a run of ordinary failures never declares the device
 * unsupported`: #3343 wired a retry counter straight to "Point & Ask can't answer on this
 * device", so three capture failures on a working Pixel 9 ended the demo with a dead end that
 * was also a lie — the report in #3407, verbatim, "dis qu'il peut pas tourner sur ce tel au
 * bout de 4 demandes".
 */
class AskFlowTest {

    private fun readyFlow() = AskFlow().apply { onAvailability(AskAvailability.Ready) }

    @Test
    fun `starts by saying the availability check is running`() {
        assertEquals(AskStep.CheckingAvailability, AskFlow().step)
    }

    @Test
    fun `each availability maps to its own card`() {
        val flow = AskFlow()
        flow.onAvailability(AskAvailability.Downloadable)
        assertEquals(AskStep.ModelDownloadable, flow.step)
        flow.onAvailability(AskAvailability.Downloading(1_024L))
        assertEquals(AskStep.ModelDownloading(1_024L), flow.step)
        flow.onAvailability(AskAvailability.Ready)
        assertEquals(AskStep.Ready, flow.step)
        flow.onAvailability(AskAvailability.Unsupported)
        assertEquals(AskStep.ModelUnsupported, flow.step)
    }

    @Test
    fun `a tap only starts a round when the model is ready`() {
        val flow = AskFlow()
        assertFalse("no model yet", flow.onTap())
        assertEquals(AskStep.CheckingAvailability, flow.step)

        flow.onAvailability(AskAvailability.Ready)
        assertTrue(flow.onTap())
        assertEquals(AskStep.CapturingFrame, flow.step)
    }

    @Test
    fun `a tap during a round is ignored rather than restarting it`() {
        val flow = readyFlow()
        flow.onTap()
        flow.onFrameAccepted()
        assertEquals(AskStep.Thinking, flow.step)
        assertFalse("a round is already in flight", flow.onTap())
        assertEquals(AskStep.Thinking, flow.step)
    }

    @Test
    fun `the happy path walks capture, thinking, streaming, answer`() {
        val flow = readyFlow()
        flow.onTap()
        assertEquals(AskStep.CapturingFrame, flow.step)
        flow.onFrameAccepted()
        assertEquals(AskStep.Thinking, flow.step)
        flow.onDelta("A wooden")
        assertEquals(AskStep.Answered("A wooden", streaming = true), flow.step)
        flow.onDelta("A wooden table")
        flow.onStreamCompleted()
        assertEquals(AskStep.Answered("A wooden table", streaming = false), flow.step)
        assertFalse(flow.isBusy)
    }

    @Test
    fun `a completed answer auto-dismisses back to ready, a streaming one does not`() {
        val flow = readyFlow()
        flow.onTap()
        flow.onFrameAccepted()
        flow.onDelta("half an answer")
        flow.onAnswerDismissed()
        assertEquals("still streaming", AskStep.Answered("half an answer", true), flow.step)
        flow.onStreamCompleted()
        flow.onAnswerDismissed()
        assertEquals(AskStep.Ready, flow.step)
    }

    @Test
    fun `a stream that completes with no text is a rephrasable failure, not a success`() {
        val flow = readyFlow()
        flow.onTap()
        flow.onFrameAccepted()
        flow.onStreamCompleted()
        assertEquals(AskStep.Failed(AskFailure.EmptyAnswer, persistent = false), flow.step)
        assertEquals(AskRecovery.Rephrase, AskFailure.EmptyAnswer.recovery)
    }

    @Test
    fun `a run of ordinary failures never declares the device unsupported`() {
        val flow = readyFlow()
        // Well past the wording threshold, and past the four rounds of #3407.
        repeat(8) {
            flow.onTap()
            flow.onFailure(AskFailure.CaptureBlank)
        }
        val step = flow.step
        assertTrue("still an ordinary failure card", step is AskStep.Failed)
        assertEquals(AskFailure.CaptureBlank, (step as AskStep.Failed).failure)
        assertEquals(
            "availability must be untouched by round failures",
            AskAvailability.Ready,
            flow.availability,
        )
    }

    @Test
    fun `repeated failures change the wording but keep the cause and the action`() {
        val flow = readyFlow()
        repeat(ASK_FAILURE_PERSISTENT_THRESHOLD - 1) {
            flow.onTap()
            flow.onFailure(AskFailure.CaptureMissingArLayer)
            assertFalse((flow.step as AskStep.Failed).persistent)
        }
        flow.onTap()
        flow.onFailure(AskFailure.CaptureMissingArLayer)
        val step = flow.step as AskStep.Failed
        assertTrue(step.persistent)
        assertEquals(
            "the persistent card still names the same cause",
            AskFailure.CaptureMissingArLayer,
            step.failure,
        )
        assertEquals(AskRecovery.Retry, step.failure.recovery)
    }

    @Test
    fun `only a terminal failure moves the demo to unsupported`() {
        val flow = readyFlow()
        flow.onTap()
        flow.onFailure(AskFailure.ModelUnavailable)
        assertEquals(AskAvailability.Unsupported, flow.availability)
        assertEquals(AskStep.ModelUnsupported, flow.step)
        assertFalse("no more rounds once the platform said no", flow.onTap())
    }

    @Test
    fun `a needs-system-update report is terminal too`() {
        val flow = readyFlow()
        flow.onTap()
        flow.onFailure(AskFailure.NeedsSystemUpdate)
        assertEquals(AskStep.ModelUnsupported, flow.step)
    }

    @Test
    fun `a failure mid-stream keeps the text already received`() {
        val flow = readyFlow()
        flow.onTap()
        flow.onFrameAccepted()
        flow.onDelta("A small wooden")
        flow.onFailure(AskFailure.Busy)
        assertEquals(AskStep.Answered("A small wooden", streaming = false), flow.step)
        assertEquals("a partial answer clears the failure run", 0, flow.consecutiveFailures)
    }

    @Test
    fun `an answer resets the failure run`() {
        val flow = readyFlow()
        repeat(2) {
            flow.onTap()
            flow.onFailure(AskFailure.CaptureFailed)
        }
        assertEquals(2, flow.consecutiveFailures)
        flow.onTap()
        flow.onFrameAccepted()
        flow.onDelta("there it is")
        assertEquals(0, flow.consecutiveFailures)
        flow.onStreamCompleted()

        flow.onTap()
        flow.onFailure(AskFailure.CaptureFailed)
        assertFalse(
            "the next failure starts a fresh run",
            (flow.step as AskStep.Failed).persistent,
        )
    }

    @Test
    fun `taking the recovery action starts the wording over`() {
        val flow = readyFlow()
        repeat(ASK_FAILURE_PERSISTENT_THRESHOLD) {
            flow.onTap()
            flow.onFailure(AskFailure.CaptureBlank)
        }
        assertTrue((flow.step as AskStep.Failed).persistent)
        flow.onRetry()
        assertEquals(AskStep.Ready, flow.step)
        flow.onTap()
        flow.onFailure(AskFailure.CaptureBlank)
        assertFalse((flow.step as AskStep.Failed).persistent)
    }

    @Test
    fun `reset clears the round and the failure history but not the availability`() {
        val flow = readyFlow()
        repeat(4) {
            flow.onTap()
            flow.onFailure(AskFailure.Unknown)
        }
        flow.reset()
        assertEquals(AskStep.Ready, flow.step)
        assertEquals(0, flow.consecutiveFailures)
        assertEquals(AskAvailability.Ready, flow.availability)
    }

    @Test
    fun `losing the model mid-round abandons the round instead of wedging it`() {
        val flow = readyFlow()
        flow.onTap()
        flow.onFrameAccepted()
        flow.onAvailability(AskAvailability.Unsupported)
        assertEquals(AskStep.ModelUnsupported, flow.step)
        assertFalse(flow.isBusy)
    }

    @Test
    fun `a late delta from an abandoned round cannot resurrect it`() {
        val flow = readyFlow()
        flow.onTap()
        flow.onFrameAccepted()
        flow.onAvailability(AskAvailability.Downloadable)
        flow.onDelta("ghost")
        assertEquals(AskStep.ModelDownloadable, flow.step)
    }

    @Test
    fun `busy covers capture, thinking and streaming only`() {
        val flow = readyFlow()
        assertFalse(flow.isBusy)
        flow.onTap()
        assertTrue(flow.isBusy)
        flow.onFrameAccepted()
        assertTrue(flow.isBusy)
        flow.onDelta("x")
        assertTrue(flow.isBusy)
        flow.onStreamCompleted()
        assertFalse(flow.isBusy)
    }

    @Test
    fun `every QA state id resolves to a step`() {
        ASK_QA_STATE_IDS.forEach { id ->
            assertTrue("id $id must resolve", askStepForQaOverride(id) != null)
        }
        assertEquals(
            "every id must name a distinct card",
            ASK_QA_STATE_IDS.size,
            ASK_QA_STATE_IDS.mapNotNull { askStepForQaOverride(it) }.distinct().size,
        )
    }

    @Test
    fun `an absent or unknown QA state id leaves the real machine alone`() {
        assertNull(askStepForQaOverride(null))
        assertNull(askStepForQaOverride(""))
        assertNull(askStepForQaOverride("   "))
        assertNull(askStepForQaOverride("not-a-state"))
    }

    @Test
    fun `QA state ids are case and whitespace tolerant`() {
        assertEquals(AskStep.Thinking, askStepForQaOverride("  Thinking "))
    }

    @Test
    fun `the QA failure states cover both wordings without claiming unsupported`() {
        val transient = askStepForQaOverride("failed") as AskStep.Failed
        val persistent = askStepForQaOverride("failed-persistent") as AskStep.Failed
        assertFalse(transient.persistent)
        assertTrue(persistent.persistent)
        assertFalse("a persistent card is never a terminal one", persistent.failure.isTerminal)
    }
}
