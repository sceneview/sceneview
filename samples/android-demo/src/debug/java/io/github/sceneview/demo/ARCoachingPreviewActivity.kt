package io.github.sceneview.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import io.github.sceneview.ar.ARCoachingOverlay
import io.github.sceneview.ar.ArGuidanceCue
import io.github.sceneview.ar.PlacementSurface
import io.github.sceneview.demo.common.placement.ARCoachingGallery
import io.github.sceneview.demo.theme.SceneViewDemoTheme

/**
 * Debug-only visual-QA host for the SDK's AR coaching overlay: renders every cue without an
 * ARCore session, over a bundled camera stand-in, so the overlay can be captured on the
 * emulator in light and dark (`adb shell cmd uimode night yes|no`).
 *
 * ```
 * adb shell am start -n io.github.sceneview.demo/.ARCoachingPreviewActivity              # gallery
 * adb shell am start -n io.github.sceneview.demo/.ARCoachingPreviewActivity \
 *     --es cue SCAN --es surface WALL                                                     # one cue, full screen
 * ```
 */
class ARCoachingPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val cue = intent.getStringExtra(EXTRA_CUE)?.let { runCatching { ArGuidanceCue.valueOf(it) }.getOrNull() }
        val surface = intent.getStringExtra(EXTRA_SURFACE)
            ?.let { runCatching { PlacementSurface.valueOf(it) }.getOrNull() }
            ?: PlacementSurface.SURFACE
        setContent {
            SceneViewDemoTheme {
                val backdrop = painterResource(R.drawable.qa_backdrop_1)
                if (cue == null) {
                    ARCoachingGallery(backdrop = backdrop)
                } else {
                    Box(Modifier.fillMaxSize()) {
                        Image(
                            painter = backdrop,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        ARCoachingOverlay(cue = cue, surface = surface)
                    }
                }
            }
        }
    }

    private companion object {
        const val EXTRA_CUE = "cue"
        const val EXTRA_SURFACE = "surface"
    }
}
