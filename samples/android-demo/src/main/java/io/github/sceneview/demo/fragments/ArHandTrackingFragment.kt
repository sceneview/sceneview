package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARHandTrackingDemo

/** Append-only fragment for the `ar-hand-tracking` demo. See [DemoFragment]. */
object ArHandTrackingFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-hand-tracking",
        titleRes = R.string.demo_ar_hand_tracking_title,
        subtitleRes = R.string.demo_ar_hand_tracking_subtitle,
        category = DemoCategory.AR_TRACKING,
        icon = Icons.Filled.BackHand,
        order = 27,
        tags = setOf("ar", "xr", "hand", "tracking", "skeleton", "headset"),
        // Live hand tracking needs an Android XR device — none in the audit
        // matrix and no public emulator yet (#1902). The demo renders a static
        // reference skeleton on phones, so it is honest "Coming Soon" rather
        // than a regression.
        status = DemoStatus.ComingSoon,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARHandTrackingDemo(onBack)
    }
}
