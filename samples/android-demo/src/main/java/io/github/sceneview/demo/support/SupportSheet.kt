@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.sceneview.demo.support

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.demo.telemetry.logOutboundLink
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens

/** Who opened the sheet: the user from About, or the moderated interstitial. */
enum class SupportSheetMode { About, Prompt }

/**
 * "Support SceneView": three amounts, one tap each straight into Google Play's purchase
 * sheet. Opened from the About card, and — as [SupportSheetMode.Prompt] — by
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
    LaunchedEffect(Unit) { SupportBilling.connect(context) }
    val dismiss = {
        SupportBilling.clearOutcome()
        onDismiss()
    }
    DemoModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SupportSheetContent(
            mode = mode,
            availability = if (SupportBilling.qaPrices != null) SupportBilling.Availability.Ready else SupportBilling.availability,
            prices = SupportBilling.qaPrices ?: SupportBilling.prices.mapValues { (_, details) -> details.formattedPrice() },
            inFlight = SupportBilling.inFlight,
            outcome = SupportBilling.outcome,
            onTierClick = { tier -> context.findActivity()?.let { SupportBilling.buy(it, tier) } },
            onOpenCollectiveClick = { openOpenCollective(context) },
            onNotNow = dismiss,
            onOptOut = {
                SupportBilling.clearOutcome()
                onOptOut()
            },
            onDone = dismiss,
        )
    }
}

/** Stateless body of [SupportSheet], shared with the previews. */
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
) {
    val navigationBars = WindowInsets.navigationBars.asPaddingValues()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SceneViewTokens.Space.lg)
            // The last row clears the gesture pill and a 3-button bar alike.
            .padding(bottom = navigationBars.calculateBottomPadding() + SceneViewTokens.Space.md),
    ) {
        AnimatedContent(targetState = outcome, label = "support-sheet") { shown ->
            when (shown) {
                SupportBilling.Outcome.Thanks -> SupportOutcome(
                    icon = Icons.Filled.Favorite,
                    title = stringResource(R.string.support_thanks_title),
                    body = stringResource(R.string.support_thanks_body),
                    onDone = onDone,
                )
                SupportBilling.Outcome.Pending -> SupportOutcome(
                    icon = Icons.Outlined.HourglassTop,
                    title = stringResource(R.string.support_pending_title),
                    body = stringResource(R.string.support_pending_body),
                    onDone = onDone,
                )
                null, SupportBilling.Outcome.Failed -> SupportAsk(
                    mode = mode,
                    availability = availability,
                    prices = prices,
                    inFlight = inFlight,
                    failed = shown == SupportBilling.Outcome.Failed,
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
    availability: SupportBilling.Availability,
    prices: Map<String, String?>,
    inFlight: String?,
    failed: Boolean,
    onTierClick: (SupportTier) -> Unit,
    onOpenCollectiveClick: () -> Unit,
    onNotNow: () -> Unit,
    onOptOut: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md)) {
        SupportHeader(
            icon = Icons.Filled.Favorite,
            title = stringResource(R.string.support_sheet_title),
            body = stringResource(R.string.support_sheet_body),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SceneViewTokens.Space.sm),
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
        ) {
            SupportTier.entries.forEach { tier ->
                SupportTierTile(
                    tier = tier,
                    price = prices[tier.productId],
                    featured = tier == SupportTier.Medium,
                    enabled = availability == SupportBilling.Availability.Ready &&
                        prices[tier.productId] != null && inFlight == null,
                    loading = availability == SupportBilling.Availability.Connecting || inFlight == tier.productId,
                    onClick = { onTierClick(tier) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val note = when {
            failed -> stringResource(R.string.support_failed)
            availability == SupportBilling.Availability.Unavailable -> stringResource(R.string.support_unavailable)
            else -> stringResource(R.string.support_sheet_note)
        }
        Text(
            text = note,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // Without Google Play there is still one way to give; never a second link.
        if (availability == SupportBilling.Availability.Unavailable) {
            OutlinedButton(
                onClick = onOpenCollectiveClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SceneViewTokens.Layout.touchTarget),
                shape = RoundedCornerShape(SceneViewTokens.Radius.md),
            ) {
                Text(stringResource(R.string.about_support_open_collective))
            }
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

/**
 * One amount. The middle one is filled (`primary`), the two others tonal: a suggested
 * default without a pre-selection, since a tap here is already the purchase.
 */
@Composable
private fun SupportTierTile(
    tier: SupportTier,
    price: String?,
    featured: Boolean,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Play can't take a purchase (no billing, no products): the tiles stay as context but read
    // as disabled — no primary fill, Material's 38 % content — so nothing looks tappable.
    val dimmed = !enabled && !loading
    val container = when {
        dimmed -> MaterialTheme.colorScheme.surfaceContainerHigh
        featured -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        dimmed -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
        featured -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val label = stringResource(tier.labelRes)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = SceneViewTokens.Space.x4l)
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(SceneViewTokens.Radius.md),
        color = container,
        contentColor = content,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = SceneViewTokens.Space.sm,
                vertical = SceneViewTokens.Space.md,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs, Alignment.CenterVertically),
        ) {
            Box(
                modifier = Modifier.heightIn(min = SceneViewTokens.Layout.dockIconSize + SceneViewTokens.Space.xs),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    LoadingIndicator(
                        modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize + SceneViewTokens.Space.xs),
                        color = content,
                    )
                } else {
                    Text(
                        text = price ?: "—",
                        style = SceneViewTokens.Type.card,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
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
            shape = RoundedCornerShape(SceneViewTokens.Radius.md),
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
    }
}

const val SUPPORT_OPEN_COLLECTIVE_URL = "https://opencollective.com/sceneview"

private val PreviewPrices = mapOf(
    SupportTier.Small.productId to "$2.99",
    SupportTier.Medium.productId to "$9.99",
    SupportTier.Large.productId to "$24.99",
)

@Preview(name = "Prompt · dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Prompt · light")
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

/** Material 3's disabled content emphasis. */
private const val DISABLED_CONTENT_ALPHA = 0.38f
