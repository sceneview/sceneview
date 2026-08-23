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
        // Deliberately NOT under AUGMENTED_REALITY: the feature lives in `sceneview` (not
        // `arsceneview`) and the demo is a non-AR studio scene — filing it under AR made
        // users expect a camera pass-through and read the screen as broken.
        category = DemoCategory.LIGHTING_ENVIRONMENT,
        icon = Icons.Filled.Gradient,
        order = 52,
        tags = setOf("shadow", "contact-shadow", "procedural", "grounding", "no-camera"),
        status = DemoStatus.InReview,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ContactShadowPreviewDemo(onBack)
    }
}
