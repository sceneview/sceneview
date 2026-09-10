package io.github.sceneview.demo

import androidx.compose.ui.graphics.Color

/**
 * SceneView brand palette, mirrored from `DESIGN.md` — the same tokens the website and docs
 * use. Every 3D demo (spheres, cubes, lines, shapes…) draws from these constants instead of
 * `Color.Red` / `Color.Green` defaults so the rendered scene matches the product identity.
 *
 * Source of truth: `DESIGN.md` → "Colors" table.
 *
 * ```
 * primary        #005bc1   SceneView blue
 * primary-hover  #0050aa   deeper blue
 * accent         #6446cd   hero-gradient end (purple)
 * accent-deep    #5a32a3   alt-hero-gradient end
 * tint-light     #a4c1ff   dark-mode primary / light tint
 * tint-soft      #d2a8ff   dark-mode gradient end
 * surface-dim    #161B22   dark surface — used for grounding planes
 * ```
 */
object SceneViewColors {
    /** Primary brand blue — #005bc1. The SceneView colour. */
    val Primary = Color(0xFF005BC1)

    /** Hover / pressed blue — #0050aa. Slightly darker than [Primary]. */
    val PrimaryHover = Color(0xFF0050AA)

    /** Hero-gradient end — #6446cd. The purple the brand gradient fades into. */
    val Accent = Color(0xFF6446CD)

    /** Alt-hero-gradient end — #5a32a3. Deeper purple for secondary hero treatments. */
    val AccentDeep = Color(0xFF5A32A3)

    /** Light-tint blue — #a4c1ff. Dark-mode primary, also used as the third ramp colour. */
    val TintLight = Color(0xFFA4C1FF)

    /** Soft-tint purple — #d2a8ff. Dark-mode gradient end, also the fourth ramp colour. */
    val TintSoft = Color(0xFFD2A8FF)

    /** Dark surface — #161B22. Used for ground planes and neutral backdrops in demos. */
    val SurfaceDim = Color(0xFF161B22)

    /**
     * Four-colour ramp used whenever a demo needs a palette of distinct but on-brand swatches
     * (PhysicsDemo spheres, GeometryDemo primitives, LinesPathsDemo lines, …). Walks from
     * [Primary] to [TintSoft] so adjacent elements always contrast enough to read.
     */
    val Ramp4 = listOf(Primary, Accent, TintLight, TintSoft)

    /**
     * Semi-transparent Primary — for AR overlays (face mesh, detected planes, …) that
     * should tint real camera imagery without occluding it. Alpha 0.4 matches the legacy
     * `#66005BC1`-ish feel that ARFaceDemo used before.
     */
    val PrimaryOverlay = Primary.copy(alpha = 0.4f)

    /**
     * Face-mesh overlay tint — [Primary] at alpha 0.55.
     *
     * Sits between [PrimaryOverlay] (0.4) and opaque: the ARCore face mesh is worn on a live
     * selfie preview that is often backlit and dark, and at 0.4 a *lit* mesh's specular sweep —
     * the cue that says "fitted 3D topology" rather than "blue filter" — washed out against it.
     * 0.55 keeps the real face readable underneath while giving the shading something to sit on
     * (#3576).
     */
    val FaceMeshOverlay = Primary.copy(alpha = 0.55f)

    /**
     * Semi-transparent Accent — secondary AR overlay tint for callouts / highlighted
     * geometry, kept distinct from [PrimaryOverlay] so stacked passes remain readable.
     */
    val AccentOverlay = Accent.copy(alpha = 0.4f)

    /**
     * Semi-transparent TintLight — used for landscape-scale AR geometry (streetscape
     * buildings) where a very low alpha is needed so the camera feed shows through.
     */
    val LandscapeOverlay = TintLight.copy(alpha = 0.3f)
}
