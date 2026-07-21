package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ContactShadowPreviewDemo

/** Append-only fragment for the `contact-shadow-preview` demo. See [DemoFragment]. */
object ContactShadowPreviewFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "contact-shadow-preview",
        titleRes = R.string.demo_contact_shadow_preview_title,
        subtitleRes = R.string.demo_contact_shadow_preview_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.Gradient,
        status = DemoStatus.InReview,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ContactShadowPreviewDemo(onBack)
    }
}
