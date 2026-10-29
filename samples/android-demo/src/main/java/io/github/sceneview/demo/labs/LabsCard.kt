package io.github.sceneview.demo.labs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.home.outlineSubtle

/**
 * The SceneView Labs card: the first section under the Home hero, and the body of the
 * deep-link gate.
 *
 * Compact on purpose (Chrome Labs' flask, a Pixel Feature Drop tile, TestFlight's invite):
 * it reads as a community invitation, never as an ad. Not joined, it says what Labs is and
 * how to get in (one report); joined, it carries the switch and what is behind it.
 */
@Composable
fun LabsHomeCard(
    access: LabsAccess,
    labsDemos: List<DemoEntry>,
    onJoinClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDemoClick: (DemoEntry) -> Unit,
    onSupportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(LabsTestTags.HOME_CARD),
        shape = RoundedCornerShape(SceneViewTokens.Home.rowRadius),
        // `surface-container` is the page colour in light; one step up keeps the card a card.
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(SceneViewTokens.Home.cardOutlineWidth, outlineSubtle()),
    ) {
        Column(
            modifier = Modifier.padding(SceneViewTokens.Space.md),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
        ) {
            LabsHeader()
            when (access) {
                LabsAccess.NotJoined -> NotJoinedBody(onJoinClick)
                LabsAccess.Off, LabsAccess.On -> JoinedBody(
                    on = access == LabsAccess.On,
                    labsDemos = labsDemos,
                    onEnabledChange = onEnabledChange,
                    onDemoClick = onDemoClick,
                    onSupportClick = onSupportClick,
                )
            }
        }
    }
}

@Composable
private fun LabsHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
    ) {
        LabsFlaskTile()
        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { heading() },
        ) {
            Text(
                text = stringResource(R.string.labs_title),
                style = SceneViewTokens.Type.card,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.labs_tag),
                style = SceneViewTokens.Type.caption,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The flask on a `primary-light` tile, the same glyph the QA-mode row uses. */
@Composable
private fun LabsFlaskTile() {
    val alpha = if (isSystemInDarkTheme()) {
        SceneViewTokens.HomeColor.primaryLightAlphaDark
    } else {
        SceneViewTokens.HomeColor.primaryLightAlphaLight
    }
    Box(
        modifier = Modifier
            .size(SceneViewTokens.Home.rowGlyph)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                shape = RoundedCornerShape(SceneViewTokens.Radius.sm),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Science,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(SceneViewTokens.Space.lg),
        )
    }
}

@Composable
private fun NotJoinedBody(onJoinClick: () -> Unit) {
    Text(
        text = stringResource(R.string.labs_invite_body),
        style = SceneViewTokens.Type.body,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Button(
        onClick = onJoinClick,
        modifier = Modifier
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .testTag(LabsTestTags.JOIN),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        Icon(
            imageVector = Icons.Outlined.BugReport,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Text(
            text = stringResource(R.string.labs_join_cta),
            modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
        )
    }
    Text(
        text = stringResource(R.string.labs_join_how),
        style = SceneViewTokens.Type.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun JoinedBody(
    on: Boolean,
    labsDemos: List<DemoEntry>,
    onEnabledChange: (Boolean) -> Unit,
    onDemoClick: (DemoEntry) -> Unit,
    onSupportClick: () -> Unit,
) {
    Text(
        text = stringResource(R.string.labs_joined_body),
        style = SceneViewTokens.Type.body,
        color = MaterialTheme.colorScheme.onSurface,
    )
    LabsSwitchRow(checked = on, onCheckedChange = onEnabledChange)
    when {
        labsDemos.isEmpty() -> Text(
            text = stringResource(R.string.labs_empty),
            style = SceneViewTokens.Type.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        !on -> Text(
            text = pluralStringResource(R.plurals.labs_count_off, labsDemos.size, labsDemos.size),
            style = SceneViewTokens.Type.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> {
            Text(
                text = pluralStringResource(R.plurals.labs_count, labsDemos.size, labsDemos.size),
                style = SceneViewTokens.Type.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            labsDemos.forEach { demo -> LabsDemoRow(demo, onClick = { onDemoClick(demo) }) }
        }
    }
    Text(
        text = stringResource(R.string.labs_help_most),
        style = SceneViewTokens.Type.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Another way to help, shown only once someone is already in: never the price of access.
    // It opens the in-app Support sheet; Open Collective appears there only when Play can't
    // take the gift, never as a link beside the in-app purchase.
    TextButton(
        onClick = onSupportClick,
        modifier = Modifier.heightIn(min = SceneViewTokens.Layout.touchTarget),
    ) {
        Text(
            text = stringResource(R.string.labs_support),
            style = SceneViewTokens.Type.caption,
        )
    }
}

/** "Show Labs features": the whole row is the switch, one focus stop for TalkBack. */
@Composable
private fun LabsSwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .testTag(LabsTestTags.SWITCH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
    ) {
        Text(
            text = stringResource(R.string.labs_switch_label),
            style = SceneViewTokens.Type.body,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
private fun LabsDemoRow(demo: DemoEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
    ) {
        Icon(
            imageVector = demo.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(SceneViewTokens.About.rowIcon),
        )
        Text(
            text = stringResource(demo.titleRes),
            style = SceneViewTokens.Type.body,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Labs" next to a demo's other badges, same pill as `StatusChip`, flask in `primary`. */
@Composable
fun LabsChip(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(SceneViewTokens.Radius.full),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        border = BorderStroke(SceneViewTokens.Home.cardOutlineWidth, outlineSubtle()),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = SceneViewTokens.Space.sm,
                vertical = SceneViewTokens.Home.badgePaddingVertical,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        ) {
            Icon(
                imageVector = Icons.Outlined.Science,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(SceneViewTokens.Home.badgeIcon),
            )
            Text(
                text = stringResource(R.string.labs_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * What a deep link to a Labs demo opens while Labs is off for this install: why the demo is
 * not there yet, and the same card as Home to get in (or switch Labs back on).
 */
@Composable
fun LabsGateBody(
    access: LabsAccess,
    labsDemos: List<DemoEntry>,
    onJoinClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onSupportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(LabsTestTags.GATE)
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(SceneViewTokens.Space.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = SceneViewTokens.Home.rowMinWidth * 2),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
        ) {
            Text(
                text = stringResource(R.string.labs_gate_title),
                style = SceneViewTokens.Type.title,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(
                    if (access == LabsAccess.NotJoined) {
                        R.string.labs_gate_body_not_joined
                    } else {
                        R.string.labs_gate_body_off
                    },
                ),
                style = SceneViewTokens.Type.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LabsHomeCard(
                access = access,
                labsDemos = labsDemos,
                onJoinClick = onJoinClick,
                onEnabledChange = onEnabledChange,
                onDemoClick = {},
                onSupportClick = onSupportClick,
            )
        }
    }
}

/**
 * The top of a Labs demo's Settings sheet: the feedback ask, prominent, in place of the plain
 * "Send feedback" row every other demo has.
 */
@Composable
fun LabsFeedbackPrompt(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(SceneViewTokens.Radius.md),
        color = MaterialTheme.colorScheme.secondaryContainer,
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
                    imageVector = Icons.Outlined.Science,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(SceneViewTokens.About.rowIcon),
                )
                Text(
                    text = stringResource(R.string.labs_sheet_title),
                    style = SceneViewTokens.Type.card,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = stringResource(R.string.labs_sheet_body),
                style = SceneViewTokens.Type.body,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(
                onClick = onClick,
                modifier = Modifier.heightIn(min = SceneViewTokens.Layout.touchTarget),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(
                    imageVector = Icons.Outlined.BugReport,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Text(
                    text = stringResource(R.string.feedback_action_cd),
                    modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                )
            }
        }
    }
}
