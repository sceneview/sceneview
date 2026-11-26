package io.github.sceneview.demo.support

import io.github.sceneview.demo.telemetry.AnalyticsEvent
import io.github.sceneview.demo.telemetry.SupportPromptSource
import io.github.sceneview.demo.telemetry.SupportResult
import io.github.sceneview.demo.telemetry.Telemetry

/**
 * One displayed support sheet: one shown event and exactly one result when it closes.
 *
 * Nothing about a purchase is logged: no product, no amount, no Play outcome. When the person
 * opened Google Play's sheet, the result is [SupportResult.Store], whatever happened there.
 */
internal class SupportPromptAnalyticsSession(
    private val source: SupportPromptSource,
    private val log: (AnalyticsEvent) -> Unit = Telemetry.analytics::log,
) {
    private var shown = false
    private var closed = false
    private var storeOpened = false

    fun shown() {
        if (shown) return
        shown = true
        log(AnalyticsEvent.SupportPromptShown(source))
    }

    /** Google Play's sheet opened for one of the amounts. Which one, and how it ended, stay out. */
    fun storeOpened() {
        if (!shown || closed) return
        storeOpened = true
    }

    /** An outbound contribution action carries the same source as the Play sheet. */
    fun openCollective() {
        if (!shown || closed) return
        close(SupportResult.OpenCollective)
    }

    /** An explicit answer wins over an earlier visit to the Play sheet. */
    fun close(result: SupportResult? = null) {
        if (!shown || closed) return
        closed = true
        log(AnalyticsEvent.SupportPromptResult(result ?: if (storeOpened) SupportResult.Store else SupportResult.Dismissed, source))
    }
}

internal val SupportSheetMode.analyticsSource: SupportPromptSource
    get() = when (this) {
        SupportSheetMode.Prompt -> SupportPromptSource.Auto
        SupportSheetMode.About -> SupportPromptSource.Settings
        SupportSheetMode.Home -> SupportPromptSource.Home
        SupportSheetMode.Menu -> SupportPromptSource.Menu
    }
