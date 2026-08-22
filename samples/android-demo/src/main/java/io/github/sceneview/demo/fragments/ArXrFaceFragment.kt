package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FaceRetouchingNatural
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARXrFaceDemo

/** Append-only fragment for the `ar-xr-face` demo. See [DemoFragment]. */
object ArXrFaceFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-xr-face",
        titleRes = R.string.demo_ar_xr_face_title,
        subtitleRes = R.string.demo_ar_xr_face_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.FaceRetouchingNatural,
        order = 54,
        tags = setOf("ar", "xr", "face", "mesh", "headset"),
        // Live face tracking needs an Android XR headset — none in the audit
        // matrix and no public emulator yet (#1903). The demo renders a static
        // reference face mesh on phones, so it is honest "Coming Soon" rather
        // than a regression.
        status = DemoStatus.ComingSoon,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARXrFaceDemo(onBack)
    }
}
