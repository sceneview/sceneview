@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.ContainerRole
import io.github.sceneview.demo.ui.cascadeIn
import io.github.sceneview.demo.ui.demoContainerTransform
import io.github.sceneview.demo.ui.rememberCascade

/** Three image-led choices; the captions grow with the system font, never over the image. */
@Composable
internal fun HomeOutcomeCarousel(
    outcomes: List<HomeOutcome>,
    entranceComplete: Boolean,
    onDemoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (outcomes.isEmpty()) return
    val home = SceneViewTokens.Home
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val captions = outcomes.map { stringResource(it.labelRes) to stringResource(it.supportingRes) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = SceneViewTokens.Space.sm
        val width = (maxWidth - gap * (outcomes.size - 1)) / outcomes.size
        val textWidth = with(density) { (width - gap * 2).roundToPx().coerceAtLeast(1) }
        // Carousel pages have one height. Measure every caption before choosing it so neither
        // the longest label nor a large accessibility font can be clipped by a shorter peer.
        val captionHeight = captions.maxOf { (title, subtitle) ->
            measurer.measure(title, MaterialTheme.typography.titleMedium,
                constraints = Constraints(maxWidth = textWidth)).size.height +
                measurer.measure(subtitle, MaterialTheme.typography.bodyMedium,
                    constraints = Constraints(maxWidth = textWidth)).size.height
        }
        val height = maxOf(home.outcomeCardHeight, home.outcomeImageHeight + gap * 2 +
            with(density) { captionHeight.toDp() })
        if (!entranceComplete) {
            Spacer(Modifier.height(height))
            return@BoxWithConstraints
        }
        val cascade = rememberCascade()
        val shape = RoundedCornerShape(home.cardRadius)
        HorizontalMultiBrowseCarousel(
            state = rememberCarouselState { outcomes.size },
            preferredItemWidth = width,
            minSmallItemWidth = width,
            maxSmallItemWidth = width,
            itemSpacing = gap,
            modifier = Modifier.fillMaxWidth().height(height).testTag(HomeTestTags.OUTCOMES),
        ) { index ->
            val outcome = outcomes[index]
            val (title, subtitle) = captions[index]
            Card(
                onClick = { onDemoClick(outcome.demoId) },
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                border = BorderStroke(home.cardOutlineWidth, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxSize()
                    .cascadeIn(cascade.delayFor(index))
                    .demoContainerTransform(outcome.demoId, ContainerRole.Card, source = "outcome")
                    .maskClip(shape)
                    .testTag(HomeTestTags.outcome(outcome.demoId))
                    .semantics { contentDescription = "$title. $subtitle" },
            ) {
                Image(
                    painterResource(homeOutcomeImage(outcome.demoId)),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = FEATURED_MEDIA_ALIGNMENT[outcome.demoId] ?: Alignment.Center,
                    modifier = Modifier.fillMaxWidth().height(home.outcomeImageHeight),
                )
                Column(Modifier.padding(gap), verticalArrangement = Arrangement.Top) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Existing media only. Placement and scan need fresh device captures before release. */
@DrawableRes
internal fun homeOutcomeImage(id: String): Int = when (id) {
    HERO_DEMO_ID -> R.drawable.home_hero_still
    // Closest bundled room render without the rejected toy car or Damaged Helmet.
    "ar-placement" -> R.drawable.preview_wall_placement_dark
    "ar-rerun" -> R.drawable.preview_ar_rerun_dark
    else -> error("No discovery image for $id")
}

internal val FEATURED_MEDIA_ALIGNMENT: Map<String, Alignment> = mapOf(
    "ar-rerun" to Alignment.CenterStart,
)
