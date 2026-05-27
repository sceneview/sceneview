@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)
@file:Suppress("MaxLineLength") // preview strings + Compose one-liner annotations are intentionally long

package io.github.sceneview.demo.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
private fun ThemeSampler() {
    Surface {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("SceneView Theme", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Card Title", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Card body text with onSurfaceVariant color.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}, shape = MaterialTheme.shapes.extraLarge) { Text("Primary") }
                FilledTonalButton(onClick = {}, shape = MaterialTheme.shapes.extraLarge) { Text("Tonal") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = true, onClick = {}, label = { Text("Selected") }, shape = MaterialTheme.shapes.extraLarge)
                FilterChip(selected = false, onClick = {}, label = { Text("Unselected") }, shape = MaterialTheme.shapes.extraLarge)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("primary", "secondary", "tertiary", "error").forEach { name ->
                    val color = when (name) {
                        "primary" -> MaterialTheme.colorScheme.primary
                        "secondary" -> MaterialTheme.colorScheme.secondary
                        "tertiary" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = color,
                        modifier = Modifier.size(48.dp)
                    ) {}
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Theme - Light")
@Composable
private fun ThemeLightPreview() {
    SceneViewDemoTheme(darkTheme = false, dynamicColor = false) {
        ThemeSampler()
    }
}

@Preview(showBackground = true, name = "Theme - Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ThemeDarkPreview() {
    SceneViewDemoTheme(darkTheme = true, dynamicColor = false) {
        ThemeSampler()
    }
}
