package io.github.erkko68.filament

/**
 * Viewport specifies the rectangular region where a View's Scene is rendered.
 *
 * The viewport defines where the content of the View (the Scene) is rendered in the render
 * target. The render target is automatically clipped to the Viewport. All coordinates are in
 * window/screen space with the origin typically at the bottom-left.
 *
 * @param left Left coordinate of the viewport (default: 0)
 * @param bottom Bottom coordinate of the viewport (default: 0)
 * @param width Width of the viewport in pixels (default: 0)
 * @param height Height of the viewport in pixels (default: 0)
 */
class Viewport(
    var left: Int = 0,
    var bottom: Int = 0,
    var width: Int = 0,
    var height: Int = 0
)
