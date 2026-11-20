package io.github.sceneview.demo.support

import com.android.billingclient.api.BillingClient.BillingResponseCode
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
    private val pendingProducts = mutableSetOf<String>()

    fun shown() {
        if (shown) return
        shown = true
        log(AnalyticsEvent.SupportPromptShown(source))
    }

    /** Records every attempt before handing control to Google Play. */
    fun purchaseLaunched(productId: String) {
        if (!shown || closed) return
        pendingProducts.remove(productId)
        purchaseResult = null
        log(AnalyticsEvent.SupportPurchaseLaunched(productId, source))
    }

    /** Pending is an intermediate purchase state, even if the sheet has already closed. */
    fun purchasePending(productId: String) {
        if (!shown || !pendingProducts.add(productId)) return
        log(AnalyticsEvent.SupportPurchasePending(productId, source))
        purchaseResult(SupportResult.PurchasePending)
    }

    /** Remembers a billing answer; the event waits until the sheet actually closes. */
    fun purchaseResult(result: SupportResult) {
        if (!shown || closed || purchaseResult?.isCompletedPurchase == true) return
        purchaseResult = result
    }

    /** An outbound contribution action carries the same source as a Play purchase. */
    fun openCollective() {
        if (!shown || closed) return
        close(SupportResult.OpenCollective)
    }

    /** Explicit prompt answers win over a preceding cancelled or failed purchase attempt. */
    fun close(result: SupportResult? = null) {
        if (!shown || closed) return
        closed = true
        log(AnalyticsEvent.SupportPromptResult(result ?: purchaseResult ?: SupportResult.Dismissed, source))
    }
}

internal val SupportSheetMode.analyticsSource: SupportPromptSource
    get() = when (this) {
        SupportSheetMode.Prompt -> SupportPromptSource.Auto
        SupportSheetMode.About -> SupportPromptSource.Settings
        SupportSheetMode.Home -> SupportPromptSource.Home
        SupportSheetMode.Menu -> SupportPromptSource.Menu
    }

internal val SupportTier.analyticsResult: SupportResult
    get() = when (this) {
        SupportTier.Tiny -> SupportResult.TierTiny
        SupportTier.Small -> SupportResult.TierSmall
        SupportTier.Medium -> SupportResult.TierMedium
        SupportTier.Large -> SupportResult.TierLarge
    }

/** Both synchronous launch failures and asynchronous Play callbacks use the same mapping. */
internal fun supportBillingFailure(responseCode: Int): SupportResult = when (responseCode) {
    BillingResponseCode.USER_CANCELED -> SupportResult.PurchaseCancelled
    BillingResponseCode.ITEM_ALREADY_OWNED -> SupportResult.PurchaseAlreadyOwned
    else -> SupportResult.PurchaseFailed
}
