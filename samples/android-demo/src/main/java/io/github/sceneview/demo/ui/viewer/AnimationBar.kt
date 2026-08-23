package io.github.sceneview.demo.ui.viewer

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.sceneview.demo.theme.SceneViewTokens

@Composable fun AnimationBar(clipNames: List<String>, selectedClip: Int, playing: Boolean, progress: Float, onPlayingChange: (Boolean) -> Unit, onClipChange: (Int) -> Unit, onProgressChange: (Float) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    // 16 dp gutters keep the scrubber thumb off the screen edge; the scaffold stacks this slot above the dock band.
    Surface(Modifier.fillMaxWidth().padding(horizontal = SceneViewTokens.Space.md), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = SceneViewTokens.Space.sm, vertical = SceneViewTokens.Space.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs)) {
            IconButton(onClick = { onPlayingChange(!playing) }, modifier = Modifier.size(SceneViewTokens.Layout.viewerAnimationButton)) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play") }
            Box { TextButton(onClick = { menu = true }) { Text(clipNames.getOrNull(selectedClip) ?: "Clip ${selectedClip + 1}") }; DropdownMenu(menu, { menu = false }) { clipNames.forEachIndexed { i, name -> DropdownMenuItem({ Text(name) }, { onClipChange(i); menu = false }) } } }
            Slider(progress, onProgressChange, Modifier.weight(1f).padding(end = SceneViewTokens.Space.sm))
        }
    }
}
