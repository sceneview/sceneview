@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.sceneview.demo.ui.viewer

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.sample.ui.LabeledSlider

data class ViewerEnvironment(val assetPath: String, val displayName: String) { val assetName get() = assetPath.substringAfterLast('/').substringBeforeLast('.') }

@Composable fun EnvironmentSheet(environments: List<ViewerEnvironment>, selectedPath: String, intensity: Float, showEnvironment: Boolean, onSelect: (ViewerEnvironment) -> Unit, onIntensity: (Float) -> Unit, onShowEnvironment: (Boolean) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = SceneViewTokens.Radius.xl, topEnd = SceneViewTokens.Radius.xl)) {
        Text("Environment", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = SceneViewTokens.Space.md))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(SceneViewTokens.Space.md), horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
            environments.forEach { env ->
                Box(Modifier.size(SceneViewTokens.Layout.viewerEnvironmentTile).background(MaterialTheme.colorScheme.surfaceDim, RoundedCornerShape(SceneViewTokens.Radius.md)).then(if (env.assetPath == selectedPath) Modifier.border(SceneViewTokens.Layout.selectedOutlineWidth, MaterialTheme.colorScheme.primary, RoundedCornerShape(SceneViewTokens.Radius.md)) else Modifier).clickable { onSelect(env) })
            }
        }
        LabeledSlider("IBL intensity", intensity, onIntensity, 0f..2f, valueText = "%.1f×".format(intensity))
        Row(Modifier.fillMaxWidth().padding(horizontal = SceneViewTokens.Space.md), horizontalArrangement = Arrangement.SpaceBetween) { Text("Show environment"); Switch(showEnvironment, onShowEnvironment) }
        TextButton(onClick = onReset, modifier = Modifier.padding(horizontal = SceneViewTokens.Space.sm)) { Text("Reset lighting") }
        Spacer(Modifier.navigationBarsPadding().height(SceneViewTokens.Space.sm))
    }
}
