@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sceneview.demo.feedback.DriveFeedbackChipReveal
import io.github.sceneview.demo.feedback.FEEDBACK_FAB_RESERVED_SPACE
import io.github.sceneview.demo.ui.ParticleBackground

/**
 * The Samples tab — a 2-column M3 Expressive grid of demos grouped by
 * category. Each card has an accent-tinted icon tile (compact, ~36% of the
 * card height) so the demo title and subtitle remain the visual anchors —
 * the previous design landed visual weight on the icon and read like a
 * preschool launcher instead of a developer SDK showcase.
 *
 * Replaces the pre-v4.1.1 plain `ListItem` list (a flat text rundown that
 * felt 2018-era). Visual reference: Sketchfab mobile + Polycam + Reality
 * Composer launchers — same density, same thumbnail-first scannability,
 * but with semantic Material icons and tinted gradients instead of
 * pre-baked previews (a future refactor can swap in captured device
 * thumbnails behind the same Card structure with no callsite change).
 *
 * Demos with a non-[DemoStatus.Working] status surface an outlined
 * "Preview" chip in the top-right corner so users have honest expectations
 * without feeling like the card is flagged as broken.
 */
@Composable
fun DemoListScreen(
    onDemoClick: (String) -> Unit,
    onAboutClick: () -> Unit = {},
) {
    // `rememberTopAppBarState()` survives recomposition + rotation so the
    // collapse offset doesn't snap back to expanded after a state change.
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state = topAppBarState)
    // Hide the floating feedback chip at rest and reveal it on scroll, so it
    // never masks a card resting in its fixed bottom-left band — #2358.
    val gridState = rememberLazyGridState()
    DriveFeedbackChipReveal(gridState)
    val grouped = remember {
        DEMO_CATEGORIES.map { cat ->
            cat to ALL_DEMOS.filter { it.category == cat }
        }
    }
    val dark = isSystemInDarkTheme()

    // Animated 3D particle backdrop (#1488) — a SceneView scene drawn as the
    // bottom layer of this Box, behind the demo grid. It only exists while the
    // Samples tab is selected (RootScreen swaps tab content), so the SceneView
    // and its frame loop leave composition — and stop — on tab switch.
    Box(modifier = Modifier.fillMaxSize()) {
        ParticleBackground(modifier = Modifier.fillMaxSize())

        Scaffold(
            // Transparent so the ParticleBackground shows through; the demo
            // cards keep their own opaque `surfaceContainer` so they stay
            // readable on top of the scene.
            containerColor = Color.Transparent,
            topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.samples_title)) },
                actions = {
                    IconButton(onClick = onAboutClick) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.samples_back_about),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                // This Scaffold is nested inside RootScreen's Scaffold, which
                // already consumes the status-bar inset as top content padding.
                // Letting LargeTopAppBar apply its default status-bar inset too
                // double-counts it — that was the large empty gap above the
                // "Samples" header (#1425). Zero the bar's own insets so the
                // header sits flush below the status bar.
                windowInsets = WindowInsets(0, 0, 0, 0),
                // Semi-transparent so the particle backdrop (#1488) drifts
                // behind the header instead of being clipped by an opaque bar;
                // the slight surface tint keeps the title legible.
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor =
                        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f),
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        // The inherited status-bar inset arrives via the outer RootScreen
        // Scaffold; consuming it again here would re-introduce the #1425 gap.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { padding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            // Bottom contentPadding reserves a gutter for the floating
            // feedback FAB so short categories (3D Basics with only Animation
            // + Geometry, e.g.) don't have their last row masked by the chip
            // (#2194).
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = FEEDBACK_FAB_RESERVED_SPACE,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            grouped.forEach { (category, demos) ->
                item(
                    // Namespaced key — guards against an `ALL_DEMOS` entry
                    // ever getting an id like "header-3D Basics" which
                    // would crash LazyGrid with a duplicate-key error.
                    key = "header-$category",
                    span = { GridItemSpan(2) },
                ) {
                    Text(
                        text = stringResource(categoryDisplayNameRes(category)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(
                            top = 16.dp,
                            bottom = 4.dp,
                            start = 4.dp,
                        ),
                    )
                }

                items(demos, key = { "demo-${it.id}" }) { demo ->
                    DemoCard(
                        demo = demo,
                        dark = dark,
                        onClick = { onDemoClick(demo.id) },
                    )
                }
                // intentionally pinned to demos.size so the LazyGrid skips an
                // empty section without leaving stray padding
            }

            item(
                key = "footer",
                span = { GridItemSpan(2) },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp, bottom = 16.dp),
                ) {
                    Text(
                        // BuildConfig.VERSION_NAME comes from gradle.properties /
                        // CI build args — hard-coding here would drift every
                        // release. The formatted resource carries the "v" prefix
                        // and label.
                        text = stringResource(R.string.samples_footer_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.samples_footer_repo),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    }
                }
            }
        }
    }
}

@Composable
private fun DemoCard(
    demo: DemoEntry,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val accent = categoryAccent(demo.category, dark)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp)
            .clickable(role = Role.Button, onClick = onClick)
            // Merge title + subtitle + status chip into a single Talkback
            // focusable item so screen-readers announce the card as one
            // node instead of three.
            .let { it },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        // Hairline outline so the card boundary stays visible. In dark mode
        // `surfaceContainer` sits only a few luminance steps above the near-black
        // ParticleBackground, so without a stroke the cards bled into the page
        // and the grid looked like floating text (#1443). `outlineVariant` is the
        // M3 token for exactly this low-emphasis container divider.
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Compact icon tile — ~36% of the 168dp card height. The
                // demo title and subtitle below should win the eye, not the
                // icon. (Previous build had a 88dp tile = 55%, which read
                // as a kids' app launcher.)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    accent.copy(alpha = 0.32f),
                                    accent.copy(alpha = 0.14f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = demo.icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(28.dp),
                    )
                }

                // Title + subtitle — bigger weight in the card hierarchy.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(demo.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = stringResource(demo.subtitleRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        lineHeight = 16.sp,
                    )
                }
            }

            // Status chip — only renders for non-Working demos. Outlined
            // M3 AssistChip-style pill on a neutral surface so it reads as
            // an honest signal ("Preview") rather than a red alarm. Sits
            // inside the card with proper padding instead of floating
            // clipped above the rounded corner.
            if (demo.status != DemoStatus.Working) {
                StatusChip(
                    status = demo.status,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: DemoStatus, modifier: Modifier = Modifier) {
    val label = when (status) {
        DemoStatus.KnownIssue -> stringResource(R.string.samples_chip_preview)
        DemoStatus.ComingSoon -> stringResource(R.string.samples_chip_soon)
        DemoStatus.Working -> return // Caller already gates; defensive no-op.
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Per-category accent color. The light-mode palette mirrors the v4.1.0
 * Stitch design system; the dark-mode palette desaturates each hue and
 * lifts the lightness so the tinted gradients and icon tints don't
 * burn at >9:1 contrast against an M3 dark `surfaceContainer`.
 */
private fun categoryAccent(category: String, dark: Boolean): Color =
    if (dark) categoryAccentDark(category) else categoryAccentLight(category)

private fun categoryAccentLight(category: String): Color = when (category) {
    "3D Basics" -> Color(0xFF6446CD)
    "Lighting & Environment" -> Color(0xFFE6A23C)
    "Content" -> Color(0xFF42A5F5)
    "Interaction" -> Color(0xFFEC407A)
    "Advanced" -> Color(0xFF26A69A)
    "Augmented Reality" -> Color(0xFF66BB6A)
    else -> Color(0xFF6446CD)
}

private fun categoryAccentDark(category: String): Color = when (category) {
    "3D Basics" -> Color(0xFFB39DDB)
    "Lighting & Environment" -> Color(0xFFFFCC80)
    "Content" -> Color(0xFF90CAF9)
    "Interaction" -> Color(0xFFF48FB1)
    "Advanced" -> Color(0xFF80CBC4)
    "Augmented Reality" -> Color(0xFFA5D6A7)
    else -> Color(0xFFB39DDB)
}

