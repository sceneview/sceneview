package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoMode
import io.github.sceneview.demo.DemoModeHost
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARHandTrackingDemo
import io.github.sceneview.demo.demos.ARXrFaceDemo

/**
 * Append-only fragment for the `ar-xr` demo. See [DemoFragment].
 *
 * Samples step 0 folded `ar-hand-tracking` (Hands mode) and `ar-xr-face` (Face mode) into this
 * one "Android XR (preview)" card: they are the only demos of `XrHandNode` and `XrFaceNode`, so
 * the two public APIs keep a demo. The retired ids still open their mode through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 *
 * The card is listed only on an Android XR device ([io.github.sceneview.demo.isXrDevice]); on a
 * phone it stays reachable by deep link and shows the static reference hand and face.
 */
object ArXrFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-xr",
        titleRes = R.string.demo_ar_xr_title,
        subtitleRes = R.string.demo_ar_xr_subtitle,
        category = DemoCategory.UNDERSTAND,
        icon = Icons.Filled.BackHand,
        order = 50,
        addedIn = "4.13.0",
        updatedIn = "4.51.0",
        tags = setOf("ar", "xr", "hand", "tracking", "skeleton", "face", "mesh", "headset"),
        // Live hand and face tracking need an Android XR device — none in the audit matrix and
        // no public emulator yet (#1902, #1903). Phones render static reference poses.
        status = DemoStatus.ComingSoon,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        DemoModeHost(
            modes = listOf(
                DemoMode("hands", R.string.demo_mode_hands),
                DemoMode("face", R.string.demo_mode_face),
            ),
            tabToMode = mapOf(0 to 0, 1 to 1),
        ) { mode ->
            when (mode) {
                1 -> ARXrFaceDemo(onBack)
                else -> ARHandTrackingDemo(onBack)
            }
        }
    }
}
