package io.github.sceneview.demo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the availability layer of Point & Ask (#2648): every way ML Kit / AICore can
 * say "not now" maps onto a named reason, a download reports a percent, and every visible
 * state has one stable logcat line.
 */
class AskUnavailableReasonTest {

    @Test
    fun `feature status maps onto engine status`() {
        assertEquals(AskEngineStatus.Unavailable(AskUnavailableReason.NotSupported), askEngineStatusOfFeatureStatus(0))
        assertEquals(AskEngineStatus.Downloadable, askEngineStatusOfFeatureStatus(1))
        assertEquals(AskEngineStatus.Downloading(), askEngineStatusOfFeatureStatus(2))
        assertEquals(AskEngineStatus.Ready, askEngineStatusOfFeatureStatus(3))
        assertEquals(
            "an unknown status is never read as ready",
            AskEngineStatus.Unavailable(AskUnavailableReason.NotSupported),
            askEngineStatusOfFeatureStatus(42),
        )
    }

    @Test
    fun `status-check error codes name their reason`() {
        assertEquals(AskUnavailableReason.AicoreNotReady, unavailableReasonOfErrorCode(601))
        assertEquals(AskUnavailableReason.AicoreNotReady, unavailableReasonOfErrorCode(606))
        assertEquals(AskUnavailableReason.NeedsSystemUpdate, unavailableReasonOfErrorCode(604))
        assertEquals(AskUnavailableReason.NotSupported, unavailableReasonOfErrorCode(8))
        assertEquals(AskUnavailableReason.NotSupported, unavailableReasonOfErrorCode(16))
        assertEquals(AskUnavailableReason.NotSupported, unavailableReasonOfErrorCode(-101))
        assertEquals(AskUnavailableReason.StatusCheckFailed, unavailableReasonOfErrorCode(null))
        assertEquals(AskUnavailableReason.StatusCheckFailed, unavailableReasonOfErrorCode(9_999))
    }

    @Test
    fun `download percent needs a known total and is clamped`() {
        assertNull(downloadPercent(null, 100L))
        assertNull(downloadPercent(10L, null))
        assertNull(downloadPercent(10L, 0L))
        assertEquals(0, downloadPercent(0L, 1_000L))
        assertEquals(37, downloadPercent(412L, 1_100L))
        assertEquals(100, downloadPercent(1_200L, 1_100L))
        assertEquals(0, downloadPercent(-5L, 1_100L))
    }

    @Test
    fun `without AICore every unavailable reason becomes no-AICore`() {
        val status = AskEngineStatus.Unavailable(AskUnavailableReason.AicoreNotReady)
        assertEquals(AskAvailability.Unsupported(AskUnavailableReason.AicoreNotReady), status.toAvailability(true))
        assertEquals(AskAvailability.Unsupported(AskUnavailableReason.NoAicore), status.toAvailability(false))
    }

    @Test
    fun `engine statuses carry download detail into availability`() {
        assertEquals(
            AskAvailability.Downloading(5L, 10L),
            AskEngineStatus.Downloading(5L, 10L).toAvailability(),
        )
        assertEquals(
            AskAvailability.DownloadFailed(AskFailure.DownloadInterrupted),
            AskEngineStatus.DownloadFailed(AskFailure.DownloadInterrupted).toAvailability(),
        )
    }

    @Test
    fun `every visible state has a stable log line`() {
        assertEquals("state=checking", AskStep.CheckingAvailability.logLine())
        assertEquals("state=ready", AskStep.Ready.logLine())
        assertEquals("state=thinking", AskStep.Thinking.logLine())
        assertEquals(
            "state=downloading bytes=412 total=1100 percent=37",
            AskStep.ModelDownloading(412L, 1_100L).logLine(),
        )
        assertEquals(
            "state=downloading bytes=? total=? percent=?",
            AskStep.ModelDownloading(null).logLine(),
        )
        assertEquals(
            "state=unavailable reason=AicoreNotReady",
            AskStep.ModelUnsupported(AskUnavailableReason.AicoreNotReady).logLine(),
        )
        assertEquals(
            "state=download-failed failure=DownloadInterrupted",
            AskStep.ModelDownloadFailed(AskFailure.DownloadInterrupted).logLine(),
        )
    }

    @Test
    fun `log key changes per whole percent and streaming flag, not per tick`() {
        assertEquals(
            AskStep.ModelDownloading(370L, 1_000L).logKey(),
            AskStep.ModelDownloading(379L, 1_000L).logKey(),
        )
        assertNotEquals(
            AskStep.ModelDownloading(379L, 1_000L).logKey(),
            AskStep.ModelDownloading(380L, 1_000L).logKey(),
        )
        assertEquals(
            AskStep.Answered("A", streaming = true).logKey(),
            AskStep.Answered("A mug", streaming = true).logKey(),
        )
        assertNotEquals(
            AskStep.Answered("A mug", streaming = true).logKey(),
            AskStep.Answered("A mug", streaming = false).logKey(),
        )
        assertNotEquals(AskStep.Ready.logKey(), AskStep.Thinking.logKey())
    }

    @Test
    fun `only reasons with an AICore lever offer the update`() {
        assertEquals(false, AskUnavailableReason.NoAicore.offersAicoreUpdate)
        assertEquals(true, AskUnavailableReason.AicoreNotReady.offersAicoreUpdate)
        assertEquals(true, AskUnavailableReason.NeedsSystemUpdate.offersAicoreUpdate)
    }
}
