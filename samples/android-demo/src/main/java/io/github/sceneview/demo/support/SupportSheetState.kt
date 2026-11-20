package io.github.sceneview.demo.support

import io.github.sceneview.demo.R

/** The actions the sheet can actually offer, using only products returned by Play. */
internal data class SupportOffer(val tier: SupportTier, val price: String)

internal enum class SupportSheetAction { Loading, Tip, OpenCollective, Thanks, Pending }

internal data class SupportSheetState(
    val action: SupportSheetAction,
    val offers: List<SupportOffer> = emptyList(),
    val selected: SupportOffer? = null,
)

internal fun supportSheetState(
    availability: SupportBilling.Availability,
    prices: Map<String, String?>,
    outcome: SupportBilling.Outcome?,
    selectedTier: SupportTier? = null,
): SupportSheetState {
    val action = when (outcome) {
        SupportBilling.Outcome.Thanks -> SupportSheetAction.Thanks
        SupportBilling.Outcome.Pending -> SupportSheetAction.Pending
        else -> when (availability) {
            SupportBilling.Availability.Connecting -> SupportSheetAction.Loading
            SupportBilling.Availability.Unavailable -> SupportSheetAction.OpenCollective
            SupportBilling.Availability.Ready -> SupportSheetAction.Tip
        }
    }
    if (action != SupportSheetAction.Tip) return SupportSheetState(action)
    val offers = SupportTier.entries.mapNotNull { tier ->
        prices[tier.productId]?.takeIf { it.isNotBlank() }?.let { SupportOffer(tier, it) }
    }
    if (offers.isEmpty()) return SupportSheetState(SupportSheetAction.OpenCollective)
    return SupportSheetState(
        action = action,
        offers = offers,
        selected = offers.firstOrNull { it.tier == selectedTier } ?: offers.first(),
    )
}

internal enum class SupportMoment { Sample, ArPlacement }

internal fun supportHeadline(mode: SupportSheetMode, moment: SupportMoment): Int = when {
    mode != SupportSheetMode.Prompt -> R.string.support_sheet_title
    moment == SupportMoment.ArPlacement -> R.string.support_sheet_title_ar
    else -> R.string.support_sheet_title_sample
}
