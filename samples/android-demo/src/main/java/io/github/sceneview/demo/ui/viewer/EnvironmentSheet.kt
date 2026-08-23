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
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.sample.ui.LabeledSlider

data class ViewerEnvironment(val assetPath: String, val displayName: String) { val assetName get() = assetPath.substringAfterLast('/').substringBeforeLast('.') }

@Composable fun EnvironmentSheet(environments: List<ViewerEnvironment>, selectedPath: String, intensity: Float, showEnvironment: Boolean, onSelect: (ViewerEnvironment) -> Unit, onIntensity: (Float) -> Unit, onShowEnvironment: (Boolean) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = SceneViewTokens.Radius.xl, topEnd = SceneViewTokens.Radius.xl)) {
        Text("Environment", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = SceneViewTokens.Space.md))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(SceneViewTokens.Space.md), horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
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
                            .background(MaterialTheme.colorScheme.surfaceDim)
                            .then(if (selected) Modifier.border(SceneViewTokens.Layout.selectedOutlineWidth, MaterialTheme.colorScheme.primary, shape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        EnvironmentThumbnails.resourceFor(env.assetName)?.let {
                            Image(painterResource(it), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        if (selected) Icon(
                            Icons.Filled.Check, null,
                            Modifier.size(SceneViewTokens.Layout.dockIconSize).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraLarge).padding(SceneViewTokens.Space.xs),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Text(
                        env.displayName, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = SceneViewTokens.Space.xs),
                    )
                }
            }
        }
        LabeledSlider("IBL intensity", intensity, onIntensity, 0f..2f, modifier = Modifier.padding(horizontal = SceneViewTokens.Space.md), valueText = "%.1f×".format(intensity))
        Row(Modifier.fillMaxWidth().padding(horizontal = SceneViewTokens.Space.md), horizontalArrangement = Arrangement.SpaceBetween) { Text("Show environment"); Switch(showEnvironment, onShowEnvironment) }
        TextButton(onClick = onReset, modifier = Modifier.padding(horizontal = SceneViewTokens.Space.sm)) { Text("Reset lighting") }
        Spacer(Modifier.navigationBarsPadding().height(SceneViewTokens.Space.sm))
    }
}
