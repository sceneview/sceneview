package io.github.sceneview.demo.ui.explore.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.sceneview.demo.R
import io.github.sceneview.demo.sources.GalleryModel
import io.github.sceneview.demo.sources.formattedFaceCount
import io.github.sceneview.demo.sources.preferredThumbnailUrl
import io.github.sceneview.demo.sources.primaryTagDisplay
import io.github.sceneview.demo.theme.SceneViewTokens

/** Media-first model tile. Metadata sits on the image instead of in an elevated container. */
@Composable
fun FeaturedModelCard(
    model: GalleryModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(SceneViewTokens.Layout.mediaAspect)
            .clip(RoundedCornerShape(SceneViewTokens.Radius.lg))
            .clickable(onClick = onClick),
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
        if (model.isAnimated) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(SceneViewTokens.Space.sm)
                    .clip(RoundedCornerShape(SceneViewTokens.Radius.full))
                    .background(MaterialTheme.colorScheme.tertiary)
                    .padding(
                        horizontal = SceneViewTokens.Space.sm,
                        vertical = SceneViewTokens.Space.xs,
                    ),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.size(SceneViewTokens.Space.md),
                )
                Text(
                    text = stringResource(R.string.explore_filter_animated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(SceneViewTokens.Space.md),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        ) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(model.primaryTagDisplay())
                    if (model.faceCount > 0) append(" · ${model.formattedFaceCount()} polys")
                },
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
