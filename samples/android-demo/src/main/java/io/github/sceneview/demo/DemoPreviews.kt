package io.github.sceneview.demo

import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource

/**
 * Captured preview images for the home grid, keyed by [DemoEntry.id].
 *
 * The map is empty until the preview-image pipeline (design spec §5) lands the
 * rendered `res/drawable-nodpi/preview_<id_with_underscores>_light.webp` /
 * `_dark.webp` pairs — 800×640 (5:4), q80. That pipeline fills [previews] in
 * the same PR it adds the drawables; nothing else writes here. Until then every
 * card falls back to its [DemoEntry.icon] tile (see `DemoMediaCard`), which is
 * why [resourceFor] returns `null` rather than a placeholder drawable: a missing
 * capture must read as "no capture yet", not as a fake one.
 */
object DemoPreviews {
    private class PreviewPair(@DrawableRes val light: Int, @DrawableRes val dark: Int)

    /** `id` → light/dark drawable pair. Filled by the image pipeline. */
    private val previews: Map<String, PreviewPair> = emptyMap()

    /** The preview drawable for [id] in the requested scheme, or `null` if none was captured. */
    @DrawableRes
    fun resourceFor(id: String, dark: Boolean = false): Int? =
        previews[id]?.let { if (dark) it.dark else it.light }
}

/**
 * The captured preview for this demo in the current colour scheme, or `null`
 * when the pipeline has not produced one — callers draw the icon tile instead.
 */
@Composable
fun DemoEntry.previewPainter(): Painter? =
    DemoPreviews.resourceFor(id, dark = isSystemInDarkTheme())?.let { painterResource(it) }
