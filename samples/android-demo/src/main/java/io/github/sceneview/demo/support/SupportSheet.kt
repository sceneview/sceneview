@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.sceneview.demo.support

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.demo.telemetry.SupportResult
import io.github.sceneview.demo.telemetry.logOutboundLink
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens

/** Where the sheet opened; only the moderated prompt offers reminder preferences. */
enum class SupportSheetMode { About, Prompt, Home, Menu }

/**
 * "Support SceneView": select an amount, then tip through Google Play's purchase sheet.
 * Opened from Home, its menu or About, and — as [SupportSheetMode.Prompt] — by
 * [SupportPrompt] after a moment of value, where it adds "Not now" / "Don't ask again".
 *
 * A bottom sheet rather than a screen: the amounts and the answers sit where a thumb
 * already is, and the user never loses the page they were on. After Play confirms a
 * purchase the same sheet turns into a thank-you; nothing in the app changes otherwise.
 */
@Composable
fun SupportSheet(
    mode: SupportSheetMode,
    onDismiss: () -> Unit,
    onOptOut: () -> Unit = {},
) {
    val context = LocalContext.current
    val analytics = remember(mode) { SupportPromptAnalyticsSession(mode.analyticsSource) }
    LaunchedEffect(Unit) { SupportBilling.connect(context) }
    DisposableEffect(analytics) {
        analytics.shown()
        SupportBilling.sheetVisible = true
        onDispose {
            SupportBilling.sheetVisible = false
            analytics.close()
        }
    }
    val dismiss: (SupportResult?) -> Unit = { result ->
        analytics.close(result)
        SupportBilling.clearOutcome()
        onDismiss()
    }
    DemoModalBottomSheet(
        onDismissRequest = { dismiss(null) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = SupportBilling.outcome != SupportBilling.Outcome.Thanks,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        SupportSheetContent(
            mode = mode,
            availability = when {
                SupportBilling.qaUnavailable -> SupportBilling.Availability.Unavailable
                SupportBilling.qaPrices != null -> SupportBilling.Availability.Ready
                else -> SupportBilling.availability
            },
            prices = SupportBilling.qaPrices ?: SupportBilling.prices.mapValues { (_, details) -> details.formattedPrice() },
            inFlight = SupportBilling.inFlight,
            outcome = SupportBilling.outcome,
            onTierClick = { tier ->
                context.findActivity()?.let { SupportBilling.buy(it, tier, analytics) }
            },
            onOpenCollectiveClick = {
                analytics.openCollective()
                SupportBilling.clearOutcome()
                onDismiss()
                openOpenCollective(context)
            },
            onNotNow = { dismiss(SupportResult.NotNow) },
            onOptOut = {
                analytics.close(SupportResult.Never)
                SupportBilling.clearOutcome()
                onOptOut()
            },
            onDone = { dismiss(null) },
            arPlacement = SupportPrompt.moment == SupportMoment.ArPlacement,
        )
    }
}

/** Body of [SupportSheet], shared with previews; the selected amount survives recreation. */
@Composable
fun SupportSheetContent(
    mode: SupportSheetMode,
    availability: SupportBilling.Availability,
    prices: Map<String, String?>,
    inFlight: String?,
    outcome: SupportBilling.Outcome?,
    onTierClick: (SupportTier) -> Unit,
    onOpenCollectiveClick: () -> Unit,
    onNotNow: () -> Unit,
    onOptOut: () -> Unit,
    onDone: () -> Unit,
    arPlacement: Boolean = false,
) {
    val navigationBars = WindowInsets.navigationBars.asPaddingValues()
    var selectedTier by rememberSaveable { mutableStateOf<SupportTier?>(null) }
    val state = supportSheetState(availability, prices, outcome, selectedTier)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SceneViewTokens.Space.md)
            // The last row clears the gesture pill and a 3-button bar alike.
            .padding(bottom = navigationBars.calculateBottomPadding() + SceneViewTokens.Space.md),
    ) {
        AnimatedContent(targetState = state, contentKey = { it.action }, label = "support-sheet") { shown ->
            when (shown.action) {
                SupportSheetAction.Thanks -> SupportOutcome(
                    icon = Icons.Filled.Favorite,
                    title = stringResource(R.string.support_thanks_title),
                    body = stringResource(R.string.support_thanks_body),
                    onDone = onDone,
                )
                SupportSheetAction.Pending -> SupportOutcome(
                    icon = Icons.Outlined.HourglassTop,
                    title = stringResource(R.string.support_pending_title),
                    body = stringResource(R.string.support_pending_body),
                    onDone = onDone,
                )
                else -> SupportAsk(
                    mode = mode,
                    state = shown,
                    arPlacement = arPlacement,
                    inFlight = inFlight,
                    failed = outcome == SupportBilling.Outcome.Failed,
                    onSelectTier = { selectedTier = it },
                    onTierClick = onTierClick,
                    onOpenCollectiveClick = onOpenCollectiveClick,
                    onNotNow = onNotNow,
                    onOptOut = onOptOut,
                )
            }
        }
    }
}

@Composable
private fun SupportAsk(
    mode: SupportSheetMode,
    state: SupportSheetState,
    arPlacement: Boolean,
    inFlight: String?,
    failed: Boolean,
    onSelectTier: (SupportTier) -> Unit,
    onTierClick: (SupportTier) -> Unit,
    onOpenCollectiveClick: () -> Unit,
    onNotNow: () -> Unit,
    onOptOut: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md)) {
        SupportHeader(
            icon = Icons.Filled.Favorite,
            title = stringResource(supportHeadline(mode, if (arPlacement) SupportMoment.ArPlacement else SupportMoment.Sample)),
            body = stringResource(R.string.support_sheet_body),
        )
        when (state.action) {
            SupportSheetAction.Tip -> {
                state.selected?.let { selected ->
                    Button(
                        onClick = { onTierClick(selected.tier) },
                        enabled = inFlight == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = SceneViewTokens.Layout.touchTarget),
                    ) {
                        Text(stringResource(R.string.support_tip, selected.price))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                ) {
                    state.offers.forEach { offer ->
                        SupportTierTile(
                            offer = offer,
                            selected = offer == state.selected,
                            enabled = inFlight == null,
                            onClick = { onSelectTier(offer.tier) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
                if (inFlight != null) {
                    LoadingIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                if (failed) {
                    Text(
                        stringResource(R.string.support_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Text(
                    stringResource(R.string.support_sheet_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SupportSheetAction.OpenCollective -> Button(
                onClick = onOpenCollectiveClick,
                modifier = Modifier.fillMaxWidth().heightIn(min = SceneViewTokens.Layout.touchTarget),
            ) {
                Text(stringResource(R.string.about_support_open_collective), textAlign = TextAlign.Center)
            }
            else -> LoadingIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        if (mode == SupportSheetMode.Prompt) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onOptOut,
                    modifier = Modifier.heightIn(min = SceneViewTokens.Layout.touchTarget),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(stringResource(R.string.support_prompt_never)) }
                TextButton(
                    onClick = onNotNow,
                    modifier = Modifier.heightIn(min = SceneViewTokens.Layout.touchTarget),
                ) { Text(stringResource(R.string.support_prompt_not_now)) }
            }
        }
    }
}

@Composable
private fun SupportHeader(icon: ImageVector, title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(SceneViewTokens.Space.x2l),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize))
            }
        }
        Text(
            text = title,
            style = SceneViewTokens.Type.title,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Select an amount without opening Play; the checked smallest available tier is the default. */
@Composable
private fun SupportTierTile(
    offer: SupportOffer,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        ),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            SceneViewTokens.Layout.hairlineWidth,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(
            modifier = Modifier.heightIn(min = SceneViewTokens.Layout.touchTarget).padding(
                horizontal = SceneViewTokens.Space.xs,
                vertical = SceneViewTokens.Space.sm,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        ) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
            )
            Text(
                offer.price,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 10.sp,
                    maxFontSize = MaterialTheme.typography.labelLarge.fontSize,
                ),
            )
            Text(stringResource(offer.tier.labelRes), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SupportOutcome(icon: ImageVector, title: String, body: String, onDone: () -> Unit) {
    Column(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.lg),
    ) {
        SupportHeader(icon = icon, title = title, body = body)
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SceneViewTokens.Layout.touchTarget),
            shape = CircleShape,
        ) { Text(stringResource(R.string.support_done)) }
    }
}

private fun com.android.billingclient.api.ProductDetails.formattedPrice(): String? =
    oneTimePurchaseOfferDetails?.formattedPrice

private fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()

private fun openOpenCollective(context: Context) {
    logOutboundLink(SUPPORT_OPEN_COLLECTIVE_URL)
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_OPEN_COLLECTIVE_URL)))
    }.onFailure {
        Toast.makeText(
            context,
            context.getString(R.string.about_no_browser, SUPPORT_OPEN_COLLECTIVE_URL),
            Toast.LENGTH_LONG,
        ).show()
    }
}

const val SUPPORT_OPEN_COLLECTIVE_URL = "https://opencollective.com/sceneview"

private val PreviewPrices = mapOf(
    SupportTier.Tiny.productId to "$0.99",
    SupportTier.Small.productId to "$2.99",
    SupportTier.Medium.productId to "$9.99",
    SupportTier.Large.productId to "$24.99",
)

@Preview(name = "Prompt · dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Prompt · light")
@Preview(name = "Prompt · large text", fontScale = 1.3f)
@Composable
private fun SupportPromptPreview() {
    SceneViewDemoTheme {
        Surface {
            SupportSheetContent(
                mode = SupportSheetMode.Prompt,
                availability = SupportBilling.Availability.Ready,
                prices = PreviewPrices,
                inFlight = null,
                outcome = null,
                onTierClick = {},
                onOpenCollectiveClick = {},
                onNotNow = {},
                onOptOut = {},
                onDone = {},
            )
        }
    }
}
