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
import io.github.sceneview.demo.common.placement.HelmetPreviewSheet
import io.github.sceneview.demo.demos.LanternPreviewSheet
import io.github.sceneview.demo.demos.WallTvPreviewSheet
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader

/**
 * Debug-only visual-QA host for the "View in 3D" preview sheets (#3884): opens the demos' real
 * sheet over a bundled camera stand-in, so it can be captured on the emulator in light and dark
 * (`adb shell cmd uimode night yes|no`).
 *
 * The helmet (feature comparisons) and lantern (cloud anchor) sheets open only from cards a live
 * AR session raises, which the emulator cannot run. The wall TV opens from its demo's settings
 * sheet too; it is here so the three can be compared side by side.
 *
 * ```
 * adb shell am start -n io.github.sceneview.demo/.PlacementPreviewQaActivity --es subject tv|helmet|lantern
 * ```
 */
class PlacementPreviewQaActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val subject = intent.getStringExtra(EXTRA_SUBJECT) ?: SUBJECT_TV
        setContent {
            SceneViewDemoTheme {
                val engine = rememberEngine()
                val modelLoader = rememberModelLoader(engine)
                val materialLoader = rememberMaterialLoader(engine)
                Box(Modifier.fillMaxSize()) {
                    Image(
                        painter = painterResource(R.drawable.qa_backdrop_1),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                when (subject) {
                    SUBJECT_HELMET -> HelmetPreviewSheet(engine, modelLoader, materialLoader, ::finish)
                    SUBJECT_LANTERN -> LanternPreviewSheet(engine, modelLoader, materialLoader, ::finish)
                    else -> WallTvPreviewSheet(engine, modelLoader, materialLoader, ::finish)
                }
            }
        }
    }

    private companion object {
        const val EXTRA_SUBJECT = "subject"
        const val SUBJECT_TV = "tv"
        const val SUBJECT_HELMET = "helmet"
        const val SUBJECT_LANTERN = "lantern"
    }
}
