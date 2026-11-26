@file:Suppress("MaxLineLength") // @Preview annotation strings and uiMode flags are intentionally long

package io.github.sceneview.demo.teamcard

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * The Home card, shown once ([TeamCardPolicy]): the question, what answering gets the team, and
 * two equal-weight ways out — write to the maintainer, or dismiss it for good. A quiet
 * `surface-container-low` card like the support entry, not a tinted banner.
 */
@Composable
fun HomeTeamCard(onMail: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(SceneViewTokens.Radius.sm),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                start = SceneViewTokens.Space.md,
                end = SceneViewTokens.Space.sm,
                top = SceneViewTokens.Space.md,
                bottom = SceneViewTokens.Space.xs,
            ),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(end = SceneViewTokens.Space.sm),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Apps,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
                ) {
                    Text(
                        text = stringResource(R.string.team_card_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.team_card_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = SceneViewTokens.Layout.touchTarget),
                ) {
                    Text(stringResource(R.string.team_card_dismiss))
                }
                FilledTonalButton(
                    onClick = onMail,
                    modifier = Modifier.heightIn(min = SceneViewTokens.Layout.touchTarget),
                ) {
                    Text(stringResource(R.string.team_card_mail))
                }
            }
        }
    }
}

/**
 * The permanent About row: same title and body as the Home card, one tap to the mail app.
 * Drawn inside an About group card, so it has no surface of its own.
 */
@Composable
fun AboutTeamRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .padding(horizontal = SceneViewTokens.Space.md, vertical = SceneViewTokens.Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
    ) {
        Icon(
            imageVector = Icons.Outlined.Apps,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(SceneViewTokens.About.rowIcon)
                .align(Alignment.Top),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        ) {
            Text(
                text = stringResource(R.string.team_card_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.team_card_body),
                style = MaterialTheme.typography.bodySmall,
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

@Composable
private fun TeamCardStates() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(SceneViewTokens.Space.md),
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
            ) {
                HomeTeamCard(onMail = {}, onDismiss = {})
                Surface(
                    shape = RoundedCornerShape(SceneViewTokens.Radius.md),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    AboutTeamRow(onClick = {})
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Team card - Home and About")
@Composable
private fun TeamCardPreview() = TeamCardStates()

@Preview(showBackground = true, name = "Team card - Home and About, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TeamCardDarkPreview() = TeamCardStates()
