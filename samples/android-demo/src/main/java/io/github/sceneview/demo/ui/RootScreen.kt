@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.sceneview.demo.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoListScreen
import io.github.sceneview.demo.R
import io.github.sceneview.demo.feedback.FEEDBACK_FAB_RESERVED_SPACE
import io.github.sceneview.demo.ui.explore.ExploreTabScreen

/**
 * Top-level UI scaffold. Hosts the four primary tabs (Explore, AR View,
 * Samples, About) under a single M3 [NavigationBar]. The Explore tab
 * (live Sketchfab catalog) is the default landing experience — it's the
 * highest-conversion surface for new users.
 *
 * Routing back into the existing per-demo screens is delegated to the
 * caller via [onDemoClick] — this composable does not own the NavHost so
 * deep-link replay (`sceneview://demo/<id>`) keeps working unchanged.
 */
@Composable
fun RootScreen(onDemoClick: (String) -> Unit) {
    var selectedTab by rememberSaveable { mutableStateOf(RootTab.Explore) }
    // Tracks whether the AR View tab is in a live camera session. When `true`
    // the bottom NavigationBar is hidden so the AR camera goes truly
    // fullscreen — pre-#2238 the nav bar always stayed visible and ate ~90 px
    // of the live camera viewport. ArViewTabContent invokes the setter
    // whenever its internal `sessionStarted` flag flips (start / exit / back
    // gesture). The flag is intentionally NOT rememberSaveable: a config
    // change or process death should land the user back on the launcher
    // screen with the nav bar visible, not on an orphaned immersive shell.
    var arSessionActive by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            // Conditional rendering rather than just `visible = !arSessionActive`
            // because the bottomBar slot reserves layout space when present —
            // hiding it via Modifier.alpha or visibility would still steal ~90 px
            // from the live AR camera viewport (#2238).
            if (!arSessionActive) {
                NavigationBar {
                    RootTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (selectedTab) {
                RootTab.Explore -> ExploreTabScreen(
                    curatedSamples = curatedSamplesForExplore(),
                    onSampleClick = { sample -> onDemoClick(sample.id) },
                )
                RootTab.ArView -> ArViewTabContent(
                    onDemoClick = onDemoClick,
                    onSessionActiveChange = { arSessionActive = it },
                )
                RootTab.Samples -> DemoListScreen(onDemoClick = onDemoClick)
                RootTab.About -> AboutTabContent()
            }
        }
    }
}

enum class RootTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    Explore(R.string.tab_explore, Icons.Filled.Search),
    ArView(R.string.tab_ar_view, Icons.Filled.ViewInAr),
    Samples(R.string.tab_samples, Icons.Filled.PlayArrow),
    About(R.string.tab_about, Icons.Filled.Info),
}

/**
 * Curated subset of [ALL_DEMOS] surfaced in the "Try a sample" carousel.
 * Hand-picked across categories so the carousel feels diverse on first
 * launch — same intent as the iOS `featuredModels` list.
 */
private fun curatedSamplesForExplore(): List<DemoEntry> {
    val ids = listOf(
        "model-viewer",
        "geometry",
        "lighting",
        "ar-placement",
        "multi-model",
        // #2239 Batch 3 — `animation` consolidated into `animation-physics`.
        "animation-physics",
    )
    return ids.mapNotNull { id -> ALL_DEMOS.firstOrNull { it.id == id } }
}

/**
 * About tab — M3 Expressive card layout that mirrors the iOS [AboutTab] structure:
 * hero card (cube icon + version pill + tagline), a column of tappable info cards
 * (Open Source, Docs, GitHub, 3D Playground, Sponsor, Credits), a "Star on GitHub"
 * primary button, and a footer.
 *
 * Pre-2026-05-11 this tab was 4 plain `Text` lines (QA finding "About tab is stark").
 * Mirroring the iOS layout brings the two platforms to visual + content parity.
 */
@Composable
private fun AboutTabContent() {
    val context = LocalContext.current
    val openLink: (String) -> Unit = { url ->
        // Devices without a browser (Android Go, stripped AOSP, user uninstalled
        // Chrome) throw ActivityNotFoundException → app crashes. runCatching +
        // toast keeps the app alive and tells the user why nothing happened. #1208
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            android.widget.Toast.makeText(
                context,
                "No browser installed to open $url",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
    // #1152 Stage 3 — CC-BY attribution for every streamed Sketchfab model
    // surfaces as a ModalBottomSheet anchored to the existing "Credits" card.
    var showCreditsSheet by rememberSaveable { mutableStateOf(false) }
    if (showCreditsSheet) {
        CreditsSheet(onDismiss = { showCreditsSheet = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Bottom padding reserves a gutter for the floating feedback FAB
            // so it does not mask the bottom "Help keep the project free &
            // active" sponsor row on first render (#2194).
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = FEEDBACK_FAB_RESERVED_SPACE,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AboutHeroCard()
        AboutInfoCard(
            icon = Icons.Filled.Favorite,
            iconColor = Color(0xFFE91E63),
            title = stringResource(R.string.about_card_open_source_title),
            subtitle = stringResource(R.string.about_card_open_source_subtitle),
        )
        AboutInfoCard(
            icon = Icons.Filled.Book,
            iconColor = Color(0xFF2196F3),
            title = stringResource(R.string.about_card_docs_title),
            subtitle = stringResource(R.string.about_card_docs_subtitle),
            trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = { openLink("https://sceneview.github.io") },
        )
        AboutInfoCard(
            icon = Icons.Filled.Code,
            iconColor = Color(0xFF5C6BC0),
            title = stringResource(R.string.about_card_github_title),
            subtitle = stringResource(R.string.about_card_github_subtitle),
            trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = { openLink("https://github.com/sceneview/sceneview") },
        )
        AboutInfoCard(
            icon = Icons.Filled.PlayArrow,
            iconColor = Color(0xFFFF9800),
            title = stringResource(R.string.about_card_playground_title),
            subtitle = stringResource(R.string.about_card_playground_subtitle),
            trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = { openLink("https://sceneview.github.io/playground.html") },
        )
        AboutInfoCard(
            icon = Icons.Filled.Favorite,
            iconColor = Color(0xFFF44336),
            title = stringResource(R.string.about_card_sponsor_title),
            subtitle = stringResource(R.string.about_card_sponsor_subtitle),
            trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = { openLink("https://github.com/sponsors/sceneview") },
        )
        AboutInfoCard(
            icon = Icons.Filled.Group,
            iconColor = Color(0xFF26A69A),
            title = stringResource(R.string.about_card_credits_title),
            subtitle = stringResource(R.string.about_card_credits_subtitle),
            trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = { showCreditsSheet = true },
        )

        Button(
            onClick = { openLink("https://github.com/sceneview/sceneview") },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(percent = 50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.about_star_on_github), style = MaterialTheme.typography.titleMedium)
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.about_made_with) + " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = Color(0xFFE91E63),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    stringResource(R.string.about_made_by),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.about_made_by_team),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutHeroCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF2196F3).copy(alpha = 0.55f),
                                Color(0xFF9C27B0).copy(alpha = 0.40f),
                            ),
                        ),
                        shape = RoundedCornerShape(28.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.ViewInAr,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
            }
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = androidx.compose.foundation.BorderStroke(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Text(
                stringResource(R.string.about_tagline_hero),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun AboutInfoCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    trailingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = iconColor.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (trailingIcon != null) {
                Icon(
                    trailingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
