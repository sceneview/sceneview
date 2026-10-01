package io.github.sceneview.demo.telemetry

import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import io.github.sceneview.ar.ARSessionFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/** The AR funnel events fed from ARSceneView's callbacks: what repeats and what logs once. */
class ArFunnelLogTest {

    private val events = mutableListOf<AnalyticsEvent>()
    private val sink = object : DemoAnalytics {
        override fun log(event: AnalyticsEvent) {
            events += event
        }
        override fun setUserProperty(property: UserProperty, value: String) = Unit
        override fun setCollectionEnabled(enabled: Boolean) = Unit
    }
    private val funnel = ArFunnelLog { sink }.apply { sampleId = "ar-placement" }

    @Test
    fun `ready and first placement log once, tracking losses every time`() {
        funnel.sessionCreated()
        funnel.trackingFailureChanged(TrackingFailureReason.INSUFFICIENT_LIGHT)
        funnel.trackingFailureChanged(null)
        funnel.placed()
        funnel.trackingFailureChanged(TrackingFailureReason.EXCESSIVE_MOTION)
        funnel.trackingFailureChanged(null)
        funnel.placed()
        assertEquals(
            listOf(
                AnalyticsEvent.ArSessionCreated("ar-placement"),
                AnalyticsEvent.ArTrackingLost("ar-placement", "insufficient_light"),
                AnalyticsEvent.ArTrackingReady("ar-placement"),
                AnalyticsEvent.ArFirstPlacement("ar-placement"),
                AnalyticsEvent.ArTrackingLost("ar-placement", "excessive_motion"),
            ),
            events,
        )
    }

    @Test
    fun `session failures are reported by type name`() {
        val failure = ARSessionFailure.DeviceNotCompatible(UnavailableDeviceNotCompatibleException())
        assertEquals("DeviceNotCompatible", ArFunnelLog.failureReason(failure))
        funnel.sessionFailed(failure)
        assertEquals(AnalyticsEvent.ArSessionFailed("ar-placement", "DeviceNotCompatible"), events.single())
    }
}
