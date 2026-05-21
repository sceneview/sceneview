@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.sceneview.demo.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.theme.SceneViewDemoTheme

@Composable
private fun UpdateBannerAvailable() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "A new version is available",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Tap Update to download it in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {},
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Update")
                }
            }
        }
    }
}

@Composable
private fun UpdateBannerDownloading(progress: Float) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Column {
                Text(
                    text = "Downloading update\u2026",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f),
                strokeCap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun UpdateBannerReadyToInstall() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Update ready!",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {},
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Restart")
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "UpdateBanner - Available")
@Composable
private fun UpdateBannerAvailablePreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { UpdateBannerAvailable() }
    }
}

@Preview(showBackground = true, name = "UpdateBanner - Downloading 30%")
@Composable
private fun UpdateBannerDownloading30Preview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { UpdateBannerDownloading(progress = 0.3f) }
    }
}

@Preview(showBackground = true, name = "UpdateBanner - Downloading 75%")
@Composable
private fun UpdateBannerDownloading75Preview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { UpdateBannerDownloading(progress = 0.75f) }
    }
}

@Preview(showBackground = true, name = "UpdateBanner - Ready to Install")
@Composable
private fun UpdateBannerReadyPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { UpdateBannerReadyToInstall() }
    }
}

@Preview(showBackground = true, name = "UpdateBanner - Dark Available", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UpdateBannerDarkAvailablePreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { UpdateBannerAvailable() }
    }
}

@Preview(showBackground = true, name = "UpdateBanner - Dark Downloading", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UpdateBannerDarkDownloadingPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { UpdateBannerDownloading(progress = 0.5f) }
    }
}

@Preview(showBackground = true, name = "UpdateBanner - Dark Ready", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UpdateBannerDarkReadyPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { UpdateBannerReadyToInstall() }
    }
}
