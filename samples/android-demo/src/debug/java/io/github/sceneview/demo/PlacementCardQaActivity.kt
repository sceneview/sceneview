package io.github.sceneview.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.PlacementSurface
import io.github.sceneview.demo.common.placement.PlacementActionCard
import io.github.sceneview.demo.common.placement.PlacementCard
import io.github.sceneview.demo.fragments.ArPlacementFragment
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * Debug-only visual QA of the "No surface found" card where the Wall mode shows it: in the
 * scaffold's bottom band, under the Place / Free pose / One call selector, over a bundled
 * camera stand-in (#4531). The card is raised by a live AR session after 10 s without a
 * surface, which the emulator cannot run (#2754).
 *
 * ```
 * adb shell am start -n io.github.sceneview.demo.qa/io.github.sceneview.demo.PlacementCardQaActivity \
 *     [--es surface WALL|SURFACE] [--ez before true]
 * ```
 *
 * `before` draws the card without its own margin, as it was before #4531: edge to edge.
 */
class PlacementCardQaActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val surface = intent.getStringExtra(EXTRA_SURFACE)
            ?.let { runCatching { PlacementSurface.valueOf(it) }.getOrNull() } ?: PlacementSurface.WALL
        val before = intent.getBooleanExtra(EXTRA_BEFORE, false)
        setContent {
            SceneViewDemoTheme {
                val switch = DemoModeSwitch(modes = ArPlacementFragment.modes, selected = 0, onSelect = {})
                CompositionLocalProvider(LocalDemoModeSwitch provides switch) {
                    DemoScaffold(
                        title = stringResource(R.string.wall_title),
                        onBack = ::finish,
                        dock = listOf(
                            DockItem(
                                icon = Icons.Filled.Refresh,
                                label = stringResource(R.string.ar_dock_reset_label),
                                onClick = {},
                            ),
                        ),
                        bottomOverlay = {
                            PlacementActionCard(
                                card = PlacementCard.NO_SURFACE,
                                onViewIn3D = {},
                                onKeepScanning = {},
                                onScanAgain = {},
                                onRestartSession = {},
                                surface = surface,
                                edgeMargin = if (before) 0.dp else SceneViewTokens.Space.md,
                            )
                        },
                    ) {
                        Image(
                            painter = painterResource(R.drawable.qa_backdrop_2),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val EXTRA_SURFACE = "surface"
        const val EXTRA_BEFORE = "before"
    }
}
