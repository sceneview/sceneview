package io.github.sceneview.demo.support

import io.github.sceneview.demo.telemetry.AnalyticsEvent
import io.github.sceneview.demo.telemetry.SupportPromptSource
import io.github.sceneview.demo.telemetry.SupportResult
import io.github.sceneview.demo.telemetry.Telemetry

/** One displayed support sheet: one shown event and exactly one result when it closes. */
internal class SupportPromptAnalyticsSession(
    private val source: SupportPromptSource,
    private val log: (AnalyticsEvent) -> Unit = Telemetry.analytics::log,
) {
    private var shown = false
    private var closed = false
    private var purchaseResult: SupportResult? = null

    fun shown() {
        if (shown) return
        shown = true
        log(AnalyticsEvent.SupportPromptShown(source))
    }

    /** Remembers a billing answer; the event waits until the sheet actually closes. */
    fun purchaseResult(result: SupportResult) {
        if (!shown || closed || purchaseResult?.isCompletedPurchase == true) return
        purchaseResult = result
    }

    /** Explicit prompt answers win over a preceding cancelled or failed purchase attempt. */
    fun close(result: SupportResult? = null) {
        if (!shown || closed) return
        closed = true
        log(AnalyticsEvent.SupportPromptResult(result ?: purchaseResult ?: SupportResult.Dismissed))
    }
}

internal val SupportSheetMode.analyticsSource: SupportPromptSource
    get() = when (this) {
        SupportSheetMode.Prompt -> SupportPromptSource.Auto
        SupportSheetMode.About -> SupportPromptSource.Settings
    }

internal val SupportTier.analyticsResult: SupportResult
    get() = when (this) {
        SupportTier.Small -> SupportResult.TierSmall
        SupportTier.Medium -> SupportResult.TierMedium
        SupportTier.Large -> SupportResult.TierLarge
    }
