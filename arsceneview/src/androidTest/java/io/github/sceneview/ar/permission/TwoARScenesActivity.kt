package io.github.sceneview.ar.permission

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import io.github.sceneview.ar.ARSceneView

/**
 * Two bare `ARSceneView`s in one activity, with nothing asking for the camera in front of
 * them: the library's own permission handling is what runs (#4467).
 *
 * A manual QA rig, in the test APK only — start it with `am start -n` after revoking the
 * camera. Each view prints the verdict it was given above itself and to logcat under
 * [TAG], so an answer that reached the wrong view is visible.
 */
class TwoARScenesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate restored=${savedInstanceState != null}")
        setContentView(
            ComposeView(this).apply {
                // Saved state is keyed by view id: without one nothing is restored.
                id = android.R.id.custom
                setContent {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .systemBarsPadding()
                    ) {
                        LabelledARView("A", Modifier.weight(1f))
                        LabelledARView("B", Modifier.weight(1f))
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy finishing=$isFinishing")
        super.onDestroy()
    }

    companion object {
        const val TAG = "ARPerm4467"
    }
}

@Composable
private fun LabelledARView(name: String, modifier: Modifier) {
    var verdict by remember { mutableStateOf("no verdict yet") }
    Column(modifier.fillMaxWidth()) {
        BasicText("View $name: $verdict", style = TextStyle(color = Color.White))
        Box(Modifier.weight(1f)) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                onCameraPermissionStateChanged = { state ->
                    verdict = when {
                        state == null -> "camera granted"
                        state.permanentlyDenied -> "refused, open settings"
                        else -> "refused, ask again"
                    }
                    Log.i(TwoARScenesActivity.TAG, "view $name: $verdict")
                },
            )
        }
    }
}
