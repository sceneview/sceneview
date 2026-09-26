package io.github.sceneview.web

/**
 * Background-colour math for the viewer's clear pass (#3879). Pure — no Filament — so the
 * jsTest suite can pin it.
 *
 * The viewer renders its [View][io.github.sceneview.web.bindings.View] in `TRANSLUCENT`
 * blend mode on a WebGL context created with `alpha: true` (premultiplied). The scene is
 * tone-mapped into the view's own buffer and composited over the canvas, which the
 * renderer clears with `clearColor` as a raw framebuffer value — no exposure, no tone
 * mapping, no sRGB conversion. So the colour handed to `setBackgroundColor` is the colour
 * on screen: `#EEF0F3` in (`238/255, 240/255, 243/255`) is `#EEF0F3` out, and an alpha
 * below `1` lets the page behind the canvas show through.
 */
object BackgroundColor {

    /**
     * Default background: `#333443`, the dark slate the viewer has always displayed. It
     * used to be the tone-mapped result of a linear `(0.05, 0.05, 0.07)` clear; stating the
     * displayed colour directly keeps every page that never sets a background unchanged.
     */
    val DEFAULT: DoubleArray = doubleArrayOf(0x33 / 255.0, 0x34 / 255.0, 0x43 / 255.0, 1.0)

    /**
     * The `clearColor` for a background of straight (non-premultiplied) sRGB components
     * `0..1`, as displayed.
     *
     * The canvas is premultiplied, so the colour channels are multiplied by alpha: an
     * unpremultiplied `(1, 1, 1, 0)` would be an invalid premultiplied value that browsers
     * composite as additive white instead of transparent. Components are clamped to
     * `0..1`; a non-finite component falls back to `0` (colour) or `1` (alpha).
     */
    fun clearColor(r: Double, g: Double, b: Double, a: Double = 1.0): DoubleArray {
        val alpha = unit(a, fallback = 1.0)
        return doubleArrayOf(
            unit(r, fallback = 0.0) * alpha,
            unit(g, fallback = 0.0) * alpha,
            unit(b, fallback = 0.0) * alpha,
            alpha,
        )
    }

    private fun unit(value: Double, fallback: Double): Double =
        if (value.isNaN() || value.isInfinite()) fallback else value.coerceIn(0.0, 1.0)
}
