package io.github.sceneview.demo.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * The home screen's single focal point (design spec §2, "Hero"): a full-span
 * image card for the Model Viewer. `radius-xl` clip, `preview_hero_model_viewer`
 * cropped to fill, a vertical scrim from transparent at 50 % to
 * `stage-scrim-end`, and bottom-left copy — `type-display` title, `type-body`
 * subtitle at 80 % white, one 44 dp "Open" pill. The whole card is one
 * clickable, one semantics node. Light: `shadow-md`; dark: 1 dp `outline-subtle`.
 *
 * The hero is dark in both themes by design — it is the one accent on a
 * white page in light mode, which is why its text colours are fixed tokens
 * ([SceneViewTokens.HomeColor]) rather than `colorScheme` roles.
 */
@Composable
fun HomeHero(
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val colors = SceneViewTokens.HomeColor
    val home = SceneViewTokens.Home
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics(mergeDescendants = true) {}
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(SceneViewTokens.Radius.xl),
        color = colors.heroField,
        shadowElevation = if (dark) 0.dp else SceneViewTokens.Elevation.md,
        border = if (dark) BorderStroke(home.cardOutlineWidth, outlineSubtle()) else null,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.preview_hero_model_viewer),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            home.heroScrimStart to SceneViewTokens.SpatialGalleryColor.stageScrimStart,
                            1f to SceneViewTokens.SpatialGalleryColor.stageScrimEnd,
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(home.heroPadding),
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                Text(
                    text = stringResource(R.string.home_hero_title),
                    style = SceneViewTokens.Type.display,
                    color = colors.heroTitle,
                )
                Text(
                    text = stringResource(R.string.home_hero_subtitle),
                    style = SceneViewTokens.Type.body,
                    color = colors.heroSubtitle,
                    maxLines = 2,
                    modifier = Modifier.widthIn(max = home.heroSubtitleMaxWidth),
                )
                Surface(
                    modifier = Modifier
                        .padding(top = SceneViewTokens.Space.sm)
                        .height(home.heroPillHeight),
                    shape = RoundedCornerShape(SceneViewTokens.Radius.full),
                    color = colors.heroPillBackground,
                    contentColor = colors.heroPillText,
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = home.heroPillPaddingHorizontal),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.home_hero_open),
                            style = SceneViewTokens.Type.body,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.heroPillText,
                        )
                    }
                }
            }
        }
    }
}
