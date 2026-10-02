@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.telemetry

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.demo.theme.SceneViewTokens

private const val PRIVACY_POLICY_URL = "https://sceneview.github.io/privacy.html"

/**
 * Hosts the usage-statistics consent sheet over Home ([onHome]), never over a sample. It shows
 * while [Telemetry.consentPending]: a first launch in the EEA, the UK or Switzerland, or after
 * [TelemetryConsent.VERSION] changed. Swiping it away or pressing Back is "Don't share", and the
 * question does not come back.
 */
@Composable
fun ConsentHost(onHome: Boolean) {
    val context = LocalContext.current
    if (Telemetry.consentPending && onHome) {
        ConsentSheet(
            onShare = { Telemetry.answerConsent(context, granted = true) },
            onDontShare = { Telemetry.answerConsent(context, granted = false) },
        )
    }
}

/**
 * "Help improve SceneView Demo" — Don't share / Share. The two answers carry the same weight:
 * same component, same width, same emphasis, side by side.
 */
@Composable
fun ConsentSheet(onShare: () -> Unit, onDontShare: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    DemoModalBottomSheet(onDismissRequest = onDontShare, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = SceneViewTokens.Space.lg)
                .padding(bottom = SceneViewTokens.Space.lg),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.Insights,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(SceneViewTokens.Space.xl),
            )
            Text(
                text = stringResource(R.string.consent_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.consent_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    // No browser (Android Go, stripped AOSP): the link does nothing rather than crash.
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
                    }
                },
                contentPadding = PaddingValues(),
                modifier = Modifier.heightIn(min = SceneViewTokens.Layout.touchTarget),
            ) {
                Text(stringResource(R.string.consent_privacy_policy))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = SceneViewTokens.Space.sm),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                // Same order as the iOS demo: decline on the start side, accept on the end side.
                ConsentButton(
                    label = stringResource(R.string.consent_dont_share),
                    onClick = onDontShare,
                    modifier = Modifier.weight(1f),
                )
                ConsentButton(
                    label = stringResource(R.string.consent_share),
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One answer. The border is `onSurfaceVariant`, not the default `outline`: the light theme's
 * outline is 1.4:1 on the sheet, under the 3:1 a control boundary needs (DESIGN.md).
 */
@Composable
private fun ConsentButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = SceneViewTokens.Layout.touchTarget),
        border = BorderStroke(SceneViewTokens.Layout.hairlineWidth, MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
