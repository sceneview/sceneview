@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.sceneview.demo.ui

import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.R
import io.github.sceneview.demo.support.SupportSheet
import io.github.sceneview.demo.support.SupportSheetMode
import io.github.sceneview.demo.telemetry.NotificationsSettingsRow
import io.github.sceneview.demo.telemetry.Telemetry
import io.github.sceneview.demo.telemetry.UsageStatisticsSettingsRow
import io.github.sceneview.demo.telemetry.logOutboundLink
import io.github.sceneview.demo.feedback.FeedbackOpenRequest
import io.github.sceneview.demo.theme.SceneViewTokens

import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSheet(onDismiss: () -> Unit, onWhatsNew: () -> Unit) {
    val context = LocalContext.current
    var showSupportSheet by rememberSaveable { mutableStateOf(false) }
    val noBrowserMessage = stringResource(R.string.about_no_browser)
    val openLink: (String) -> Unit = { url ->
        logOutboundLink(url)
        // Devices without a browser (Android Go, stripped AOSP, uninstalled Chrome)
        // throw ActivityNotFoundException → the app crashes. #1208
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            android.widget.Toast.makeText(
                context,
                noBrowserMessage.format(url),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
    // The Play Store app first — it opens on the listing with no chooser — then the same
    // listing through `openLink`, which carries the no-browser fallback. Logged once either
    // way, as a `store` outbound link like every other one on this screen.
    val openStoreListing: (String) -> Unit = { packageName ->
        val marketUrl = "market://details?id=$packageName"
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(marketUrl)))
        }.isSuccess
        if (opened) {
            logOutboundLink(marketUrl)
        } else {
            openLink("https://play.google.com/store/apps/details?id=$packageName")
        }
    }
    // #1152 Stage 3 — CC-BY attribution for every streamed Sketchfab model.
    var showCreditsSheet by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = SceneViewTokens.Space.md,
                    end = SceneViewTokens.Space.md,
                    top = SceneViewTokens.Space.sm,
                    bottom = LIST_BOTTOM_GUTTER,
                ),
            // `space-xl` between blocks, `space-sm` between a group label and its card:
            // the label has to belong to the card under it, not float between two of them.
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xl),
        ) {
            AboutIdentity()
            AboutSupportCard(onSupportInApp = { showSupportSheet = true })
            AboutGroup(title = stringResource(R.string.about_group_more_apps)) {
                MORE_APPS.forEachIndexed { index, app ->
                    if (index > 0) AboutRowDivider(startInset = SceneViewTokens.About.appDividerInset)
                    AboutAppRow(app = app, onClick = { openStoreListing(app.packageName) })
                }
            }
            AboutGroup(title = stringResource(R.string.about_group_learn)) {
                AboutActionRow(
                    icon = Icons.Outlined.MenuBook,
                    title = stringResource(R.string.about_card_docs_title),
                    onClick = { openLink("https://sceneview.github.io") },
                )
                AboutRowDivider()
                AboutActionRow(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.about_source_title),
                    onClick = { openLink("https://github.com/sceneview/sceneview") },
                )
            }
            AboutGroup(title = stringResource(R.string.about_group_app)) {
                AboutActionRow(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.about_card_feedback_title),
                    external = false,
                    onClick = { FeedbackOpenRequest.request() },
                )
                AboutRowDivider()
                AboutActionRow(
                    icon = Icons.Outlined.History,
                    title = stringResource(R.string.whats_new_title),
                    external = false,
                    onClick = onWhatsNew,
                )
            }
            // Only in a build that carries a Firebase config: without one there is nothing to
            // collect and no push can arrive, so a switch would promise something false.
            if (Telemetry.firebaseAvailable) {
                AboutGroup(title = stringResource(R.string.about_group_privacy)) {
                    UsageStatisticsSettingsRow()
                    AboutRowDivider()
                    NotificationsSettingsRow()
                }
            }
            AboutGroup(title = stringResource(R.string.about_group_legal)) {
                AboutActionRow(
                    icon = Icons.Outlined.Description,
                    title = stringResource(R.string.consent_privacy_policy),
                    onClick = { openLink("https://sceneview.github.io/privacy.html") },
                )
                AboutRowDivider()
                AboutActionRow(
                    icon = Icons.Outlined.Description,
                    title = stringResource(R.string.about_license_title),
                    onClick = { openLink("https://github.com/sceneview/sceneview/blob/main/LICENSE") },
                )
                AboutRowDivider()
                AboutActionRow(
                    icon = Icons.Outlined.Groups,
                    title = stringResource(R.string.about_credits_attribution),
                    external = false,
                    onClick = { showCreditsSheet = true },
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
            ) {
                Text(
                    text = stringResource(R.string.about_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.about_author_positioning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.about_built_with),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
    }
    }
    if (showSupportSheet) {
        SupportSheet(mode = SupportSheetMode.About, onDismiss = { showSupportSheet = false })
    }
    if (showCreditsSheet) {
        CreditsSheet(onDismiss = { showCreditsSheet = false })
    }

}

/**
 * Identity block — the mark, the name, the installed version, one sentence.
 *
 * Deliberately **not** a card. A slab here would be a second emphasised surface
 * competing with the support card directly below it, which is the mistake the old
 * screen made; on the page background the block reads as a masthead instead.
 */
@Composable
private fun AboutIdentity() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
    ) {
        Image(
            // The launcher icon itself (#3563), not a Material glyph on a gradient —
            // Shared with the installed app identity.
            painter = painterResource(R.drawable.ic_sceneview_hero),
            contentDescription = null,
            modifier = Modifier
                .size(SceneViewTokens.About.markSize)
                .clip(RoundedCornerShape(SceneViewTokens.Radius.xl)),
        )
        Text(
            text = stringResource(R.string.about_sceneview),
            style = SceneViewTokens.Type.title,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            // Version only. The licence has its own row in the Legal group; naming it
            // twice on one screen is the redundancy this redesign removes.
            text = stringResource(R.string.about_identity_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.about_identity_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The one emphasised surface on the screen (#3565).
 *
 * One button, into the Support sheet: three amounts, one tap each into Google Play, the
 * fastest way to give from a phone. Google Play's policy keeps external donation links away
 * from an in-app purchase, so Open Collective appears only inside the sheet, and only when
 * Play can't take the gift. No urgency copy and nothing about revenue: the card states what
 * funding buys and stops.
 */
@Composable
private fun AboutSupportCard(onSupportInApp: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(SceneViewTokens.Radius.lg),
        // `secondaryContainer`, not `primaryContainer`: the primary container is
        // #00448D in the dark scheme — a saturated blue slab that reads as a banner
        // ad, which is the opposite of "non-intrusive" (#3565). The secondary
        // container is calm in both themes and still the only tinted surface here,
        // so the card stays the one thing the eye lands on after the mark.
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(SceneViewTokens.Space.md),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                Icon(
                    imageVector = Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(SceneViewTokens.About.rowIcon),
                )
                Text(
                    text = stringResource(R.string.about_support_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = stringResource(R.string.about_support_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onSupportInApp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SceneViewTokens.Layout.touchTarget),
                shape = RoundedCornerShape(SceneViewTokens.Radius.md),
            ) {
                Text(
                    text = stringResource(R.string.about_support_in_app),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A titled group of rows, drawn as one `surfaceContainer` card with inset hairlines
 * between its children — so the rows read as a list with a shape, and the label
 * belongs to the card under it rather than floating between two of them.
 */
@Composable
private fun AboutGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier
                .padding(
                    start = SceneViewTokens.Space.md,
                    bottom = SceneViewTokens.Space.sm,
                )
                .semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(SceneViewTokens.Radius.md),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(content = content)
        }
    }
}

/** Hairline between two rows of an [AboutGroup], inset past the leading glyph. */
@Composable
private fun AboutRowDivider(startInset: Dp = SceneViewTokens.About.dividerInset) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startInset),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * One tappable row. [external] picks the affordance, which is the row's only
 * promise about what a tap does: open-in-new leaves the app for a browser, a
 * chevron stays inside it (the feedback flow, the credits sheet).
 */
@Composable
private fun AboutActionRow(
    icon: ImageVector,
    title: String,
    external: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .padding(
                horizontal = SceneViewTokens.Space.md,
                vertical = SceneViewTokens.Space.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
    ) {
        Icon(
            imageVector = icon,
            // Decorative: the row merges its children, so the title below is already
            // the accessible name. A description here would read it twice.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SceneViewTokens.About.rowIcon),
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            imageVector = if (external) {
                Icons.AutoMirrored.Outlined.OpenInNew
            } else {
                Icons.AutoMirrored.Outlined.KeyboardArrowRight
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            // The chevron is a thinner glyph than open-in-new, so it is drawn a step
            // larger: matched boxes would read as two different icon sets in one column.
            modifier = Modifier.size(
                if (external) SceneViewTokens.About.rowAffordance else SceneViewTokens.About.rowIcon,
            ),
        )
    }
}

/**
 * Another published app built with SceneView, as one row of the "More apps" group.
 *
 * [packageName] is the whole link: the row opens that package's Google Play listing.
 */
private data class MoreApp(
    val packageName: String,
    @DrawableRes val icon: Int,
    @StringRes val name: Int,
    @StringRes val description: Int,
)

/**
 * The maintainer's other apps on Google Play, both built on this SDK. Each [MoreApp.icon]
 * is that app's own launcher icon, taken from its listing — never a stand-in glyph.
 */
private val MORE_APPS = listOf(
    MoreApp(
        packageName = "com.gorisse.thomas.arcamera",
        icon = R.drawable.about_app_ar_model_viewer,
        name = R.string.about_app_ar_model_viewer_name,
        description = R.string.about_app_ar_model_viewer_description,
    ),
    MoreApp(
        packageName = "com.gorisse.thomas.willitfit",
        icon = R.drawable.about_app_will_it_fit,
        name = R.string.about_app_will_it_fit_name,
        description = R.string.about_app_will_it_fit_description,
    ),
)

/**
 * One app of the "More apps" group: its launcher icon, its name, one line on what it
 * does, and the same open-in-new affordance as every row that leaves the app.
 *
 * It lives in a plain [AboutGroup] on purpose. The support card is the one emphasised
 * surface of the screen (#3565); a second tinted card for these would compete with it,
 * so the only colour here is the icons themselves. No price, no badge, no "new".
 */
@Composable
private fun AboutAppRow(app: MoreApp, onClick: () -> Unit) {
    val iconShape = RoundedCornerShape(SceneViewTokens.Radius.xs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.about_more_apps_open),
                onClick = onClick,
            )
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            // `space-md` on all four sides, where an action row takes `space-sm`
            // vertically: this row is three lines of text, and at 8 dp the name sits
            // against the card's rounded corner.
            .padding(SceneViewTokens.Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
    ) {
        Image(
            painter = painterResource(app.icon),
            // Decorative for the same reason as an action row's glyph: the name beside
            // it is already the row's accessible name.
            contentDescription = null,
            modifier = Modifier
                .size(SceneViewTokens.About.appIcon)
                // Both icons are dark tiles; on the dark `surface-container` their edge
                // is a tonal step too small to read, so a hairline draws it. Around the
                // icon, not over it: painted on top, the light-theme hairline is a pale
                // ring eating into a dark picture.
                .border(
                    width = SceneViewTokens.Layout.hairlineWidth,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = iconShape,
                )
                .padding(SceneViewTokens.Layout.hairlineWidth)
                .clip(iconShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(app.name),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(app.description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(SceneViewTokens.About.rowAffordance),
        )
    }
}
