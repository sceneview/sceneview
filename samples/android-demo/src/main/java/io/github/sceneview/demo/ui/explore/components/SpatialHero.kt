package io.github.sceneview.demo.ui.explore.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.sceneview.demo.R
import io.github.sceneview.demo.sources.GalleryModel
import io.github.sceneview.demo.sources.preferredThumbnailUrl
import io.github.sceneview.demo.theme.SceneViewTokens

/** Static, media-led opening stage. Intentionally contains no SceneView/Filament rendering. */
@Composable
fun SpatialHero(model: GalleryModel, onViewIn3D: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SceneViewTokens.Layout.heroStageHeight)
            .clip(RoundedCornerShape(SceneViewTokens.Radius.xl))
            .background(MaterialTheme.colorScheme.surfaceDim),
    ) {
        AsyncNetworkImage(
            url = model.preferredThumbnailUrl(),
            contentDescription = model.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            SceneViewTokens.SpatialGalleryColor.stageScrimStart,
                            SceneViewTokens.SpatialGalleryColor.stageScrimEnd,
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(SceneViewTokens.Space.lg),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
        ) {
            Text(
                text = model.sourceId.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(SceneViewTokens.Radius.full))
                    .background(SceneViewTokens.SpatialGalleryColor.glassSurfaceDark)
                    .padding(horizontal = SceneViewTokens.Space.sm, vertical = SceneViewTokens.Space.xs),
            )
            Text(
                text = model.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                Button(onClick = onViewIn3D, shape = RoundedCornerShape(SceneViewTokens.Radius.full)) {
                    Icon(Icons.Filled.ViewInAr, contentDescription = null)
                    Text(
                        text = stringResource(R.string.explore_view_in_3d),
                        modifier = Modifier.padding(start = SceneViewTokens.Space.sm),
                    )
                }
            }
        }
    }
}
