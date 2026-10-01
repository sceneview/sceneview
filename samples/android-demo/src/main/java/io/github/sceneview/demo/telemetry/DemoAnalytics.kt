package io.github.sceneview.demo.telemetry

/**
 * The one door every screen goes through to report usage. Screens call [Telemetry.analytics];
 * they never touch Firebase themselves, so the vendor stays swappable and every screen stays
 * testable with a recording fake.
 *
 * Implementations must never throw: analytics is not allowed to crash a demo.
 */
interface DemoAnalytics {
    fun log(event: AnalyticsEvent)

    fun setUserProperty(property: UserProperty, value: String)

    /**
     * Turns usage statistics and crash reports on or off together (About → Privacy &
     * notifications). Re-applied at every launch.
     */
    fun setCollectionEnabled(enabled: Boolean)

    /**
     * Clears the analytics data waiting on the device and resets the app-instance id, so
     * nothing collected before an opt-out can be joined to anything collected after an opt-in.
     * Called once, when the switch goes from on to off.
     */
    fun resetData()
}

/** Used when this build has no Firebase config, or Firebase failed to start. Does nothing. */
object NoOpDemoAnalytics : DemoAnalytics {
    override fun log(event: AnalyticsEvent) = Unit

    override fun setUserProperty(property: UserProperty, value: String) = Unit

    override fun setCollectionEnabled(enabled: Boolean) = Unit

    override fun resetData() = Unit
}

/**
 * Wraps another implementation and drops every call while collection is off, so a screen that
 * logs after the user opted out sends nothing even before Firebase has processed the opt-out.
 * Also where the taxonomy's length limits are enforced for every sink.
 */
class GatedDemoAnalytics(
    private val delegate: DemoAnalytics,
    private val isEnabled: () -> Boolean,
) : DemoAnalytics {
    override fun log(event: AnalyticsEvent) {
        if (!isEnabled()) return
        if (event.name.length > AnalyticsEvent.MAX_NAME) return
        runCatching { delegate.log(event) }
    }

    override fun setUserProperty(property: UserProperty, value: String) {
        if (!isEnabled()) return
        runCatching { delegate.setUserProperty(property, value.take(USER_PROPERTY_MAX_VALUE)) }
    }

    override fun setCollectionEnabled(enabled: Boolean) {
        runCatching { delegate.setCollectionEnabled(enabled) }
    }

    /** Not gated: it runs right after collection was turned off. */
    override fun resetData() {
        runCatching { delegate.resetData() }
    }

    private companion object {
        /** Firebase's cap on a user-property value. */
        const val USER_PROPERTY_MAX_VALUE = 36
    }
}
