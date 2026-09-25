@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.sceneview.demo.ui.viewer

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.sample.ui.LabeledSlider

data class ViewerEnvironment(val assetPath: String, val displayName: String) { val assetName get() = assetPath.substringAfterLast('/').substringBeforeLast('.') }

@Composable fun EnvironmentSheet(environments: List<ViewerEnvironment>, selectedPath: String, intensity: Float, showEnvironment: Boolean, onSelect: (ViewerEnvironment) -> Unit, onIntensity: (Float) -> Unit, onShowEnvironment: (Boolean) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    DemoModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = SceneViewTokens.Radius.xl, topEnd = SceneViewTokens.Radius.xl),
    ) {
        Text("Lighting", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = SceneViewTokens.Space.md))
        // The row is almost always wider than the viewport (7 bundled environments at
        // `viewerEnvironmentTile` + `Space.sm` apiece), so it always needs a scroll hint.
        // A plain `fillMaxWidth()` viewport leaves that to chance: on this sheet's own
        // width the last whole tile happened to land within a few dp of the trailing
        // edge, so nothing was ever visibly cut off and the row read as complete (#3797).
        // `BoxWithConstraints` measures the true available width and clips the viewport
        // to end exactly half a tile past the last whole one — the next environment is
        // always visibly cut, the way Sketchfab and Polycam hint a scrollable strip.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tile = SceneViewTokens.Layout.viewerEnvironmentTile
            val gap = SceneViewTokens.Space.sm
            val margin = SceneViewTokens.Space.md
            val step = tile + gap
            val wholeTiles = ((maxWidth - margin - tile / 2) / step).toInt().coerceAtLeast(0)
            val viewportWidth = (margin + step * wholeTiles + tile / 2).coerceAtMost(maxWidth)
            Row(
                Modifier.width(viewportWidth).horizontalScroll(rememberScrollState())
                    .padding(horizontal = margin, vertical = SceneViewTokens.Space.md),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                environments.forEach { env ->
                    val selected = env.assetPath == selectedPath
                    val shape = RoundedCornerShape(SceneViewTokens.Radius.md)
                    Column(
                        Modifier.width(SceneViewTokens.Layout.viewerEnvironmentTile)
                            .semantics(mergeDescendants = true) { contentDescription = env.displayName }
                            .clickable { onSelect(env) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(SceneViewTokens.Layout.viewerEnvironmentTile).clip(shape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(
                                    // Unselected tiles used to have no edge at all: in dark the
                                    // tile and the sheet under it were the same tone, so an
                                    // unselected environment read as a gap. The hairline is the
                                    // complement to the tonal step, not a replacement for it.
                                    width = if (selected) {
                                        SceneViewTokens.Layout.selectedOutlineWidth
                                    } else {
                                        SceneViewTokens.Layout.hairlineWidth
                                    },
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = shape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            EnvironmentThumbnails.resourceFor(env.assetName)?.let {
                                Image(
                                    painterResource(it), null,
                                    Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                                )
                            }
                            if (selected) Icon(
                                Icons.Filled.Check, null,
                                Modifier.size(SceneViewTokens.Layout.dockIconSize).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraLarge).padding(SceneViewTokens.Space.xs),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        // Two lines rather than one (#3797): at one line "Chinese Garden"
                        // and "Outdoor Cloudy" both clipped to their first word, and
                        // "Studio" / "Studio Warm" then read as the same tile twice.
                        Text(
                            env.displayName, style = MaterialTheme.typography.labelMedium,
                            maxLines = 2, textAlign = TextAlign.Center,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = SceneViewTokens.Space.xs),
                        )
                    }
                }
            }
        }
        LabeledSlider("IBL intensity", intensity, onIntensity, 0f..2f, modifier = Modifier.padding(horizontal = SceneViewTokens.Space.md), valueText = "%.1f×".format(intensity))
        Row(Modifier.fillMaxWidth().padding(horizontal = SceneViewTokens.Space.md), horizontalArrangement = Arrangement.SpaceBetween) { Text("Show environment"); Switch(showEnvironment, onShowEnvironment) }
        TextButton(onClick = onReset, modifier = Modifier.padding(horizontal = SceneViewTokens.Space.sm)) { Text("Reset lighting") }
        Spacer(Modifier.navigationBarsPadding().height(SceneViewTokens.Space.sm))
    }
}
