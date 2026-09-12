package io.github.sceneview.demo.demos.internal

import java.nio.ByteBuffer

/**
 * Pure-Kotlin helpers for the ARCore Scene Semantics label-overlay demo (#1730 / #1868).
 *
 * ARCore's [com.google.ar.core.Frame.acquireSemanticImage] returns an `R8` image — one byte
 * per pixel, the byte being a [com.google.ar.core.SemanticLabel] ordinal (`0..11`). The
 * `ARSceneSemanticsDemo` colours that raster into an `ARGB_8888` [android.graphics.Bitmap] via
 * [labelBufferToArgb] and composites it as a Compose `Image` **on top of** the `ARSceneView** —
 * the same architecture [DepthVisualization] already uses for the depth-visualization demo.
 *
 * This demo used to upload the raster into a Filament `R8` texture and paint a camera-parented
 * 3D quad with a custom `.filamat` material instead (#1868's original design). That never
 * actually composited over the live camera feed: `ARCameraStream`'s flat (non-occlusion)
 * material is deliberately drawn **last** (`RenderableManager` priority 7) so it can early-Z
 * reject pixels already covered by opaque virtual geometry (#1617) — but the overlay quad's
 * material set `depthWrite: false` (it has to, to let `UNLABELED` pixels stay transparent
 * without leaving a see-through "hole" behind them), so it never left anything in the depth
 * buffer for the camera pass to reject against. The camera quad then unconditionally overdrew
 * the overlay every frame, on every device, regardless of `SemanticMode` support or opacity —
 * this is the actual defect behind
 * [#3396](https://github.com/sceneview/sceneview/issues/3396) and
 * [#3527](https://github.com/sceneview/sceneview/issues/3527) ("overlay not visible" /
 * "nothing renders"). Compositing in Compose, above the `SurfaceView`/`TextureView` entirely,
 * sidesteps Filament's render-order bookkeeping the same way the depth demo already does.
 *
 * Two things need a non-Filament, JVM-testable home:
 *
 *  1. [labelBufferToArgb] — colours a **row-strided** `R8` label buffer
 *     (`rowStride >= width`) into a packed `ARGB_8888` pixel array, rotated to match the
 *     display (ARCore hands the raster out in the landscape camera-sensor frame — same
 *     mismatch [DepthVisualization.depthBufferToArgb] corrects for depth, #3184).
 *  2. [PALETTE_ARGB] — the 12-class colour palette, used both by [labelBufferToArgb] and the
 *     demo's on-screen colour legend.
 *
 * Extracted as an `internal object` so the logic is unit-testable without ARCore on the
 * classpath — `com.google.ar.core.Image` is not mockable on the JVM, but a plain [ByteBuffer]
 * is. See `SemanticsOverlayTest`.
 *
 * Closes part of [#1868](https://github.com/sceneview/sceneview/issues/1868).
 */
internal object SemanticsOverlay {

    /** Number of ARCore Scene Semantics outdoor classes (`SemanticLabel` enum size). */
    const val LABEL_COUNT: Int = 12

    /** Ordinal of the `UNLABELED` class — rendered fully transparent by the overlay shader. */
    const val UNLABELED_ORDINAL: Int = 11

    /**
     * Per-class `0xFFRRGGBB` colours for the 12 ARCore semantic labels, indexed by ordinal:
     *
     * `0 SKY · 1 BUILDING · 2 TREE · 3 ROAD · 4 SIDEWALK · 5 TERRAIN · 6 STRUCTURE · 7 OBJECT ·
     * 8 VEHICLE · 9 PERSON · 10 WATER · 11 UNLABELED`.
     *
     * Mirrors `const vec3 SEMANTIC_PALETTE[12]` in
     * `sceneview/src/main/materials/semantics_overlay.mat`. The `.mat` shader is the source of
     * truth; this table only backs the demo's on-screen colour legend — keep the two in sync.
     */
    val PALETTE_ARGB: IntArray = intArrayOf(
        0xFF66B0F5.toInt(), // SKY        — light blue
        0xFFD95F57.toInt(), // BUILDING   — terracotta
        0xFF4CB050.toInt(), // TREE       — green
        0xFF757575.toInt(), // ROAD       — grey
        0xFFBDBD8C.toInt(), // SIDEWALK   — khaki
        0xFFCC9E6B.toInt(), // TERRAIN    — tan
        0xFF9E75BD.toInt(), // STRUCTURE  — purple
        0xFFFFC207.toInt(), // OBJECT     — amber
        0xFF2196F2.toInt(), // VEHICLE    — strong blue
        0xFFF54290.toInt(), // PERSON     — pink
        0xFF00BDD4.toInt(), // WATER      — cyan
        0xFF000000.toInt()  // UNLABELED  — black
    )

    /** Human-readable label names indexed by ordinal — matches `SemanticLabel.values()`. */
    val LABEL_NAMES: Array<String> = arrayOf(
        "Sky", "Building", "Tree", "Road", "Sidewalk", "Terrain",
        "Structure", "Object", "Vehicle", "Person", "Water", "Unlabeled"
    )

    /** Sentinel written for [UNLABELED_ORDINAL] pixels — fully transparent. */
    private const val ARGB_TRANSPARENT: Int = 0x00000000

    /**
     * Clockwise rotation, in degrees, that makes an ARCore semantic image upright on screen for
     * a given `android.view.Surface.ROTATION_*` display rotation.
     *
     * ARCore hands the semantic raster out in the **camera sensor** frame, which is landscape
     * and does not follow the display — same mismatch as the depth image
     * ([DepthVisualization.displayRotationToDegrees], #3184). The back-camera sensor
     * orientation is 90° on every Android phone this demo targets.
     *
     * @param surfaceRotation One of the `Surface.ROTATION_*` **ordinals** (0, 1, 2, 3) — not
     *                        degrees. An unknown value falls back to the portrait mapping.
     */
    fun displayRotationToDegrees(surfaceRotation: Int): Int =
        DepthVisualization.displayRotationToDegrees(surfaceRotation)

    /** Width of a `width` × `height` image after [rotationDegrees] of clockwise rotation. */
    fun rotatedWidth(width: Int, height: Int, rotationDegrees: Int): Int =
        DepthVisualization.rotatedWidth(width, height, rotationDegrees)

    /** Height of a `width` × `height` image after [rotationDegrees] of clockwise rotation. */
    fun rotatedHeight(width: Int, height: Int, rotationDegrees: Int): Int =
        DepthVisualization.rotatedHeight(width, height, rotationDegrees)

    /**
     * Colours a row-strided single-channel (`R8`) ARCore Scene Semantics label buffer into a
     * packed `ARGB_8888` pixel array suitable for `Bitmap.setPixels`, rotated to match the
     * display so the overlay lines up with the live camera feed underneath it (#3184-class
     * mismatch — see [displayRotationToDegrees]).
     *
     * Each source byte is a [com.google.ar.core.SemanticLabel] ordinal (`0..11`); it is mapped
     * through [PALETTE_ARGB], with [UNLABELED_ORDINAL] painted fully transparent so un-classified
     * pixels show the live camera through instead of a solid colour. The demo composites the
     * result via Compose `Image(alpha = blend)`, on top of the `ARSceneView` — see the class
     * KDoc above for why a Filament 3D quad could never make this composite over the camera feed.
     *
     * With a non-zero [rotationDegrees] the samples are written straight into their rotated
     * destination index, so making the overlay upright costs no extra pass and no second buffer.
     * The returned array is then [rotatedWidth] × [rotatedHeight] — for a quarter turn those are
     * the *swapped* source dimensions, and the caller's bitmap must be allocated to match.
     *
     * @param labelBytes     direct buffer with ARCore's `R8` semantic data; not consumed.
     * @param width          pixel width of the semantic image.
     * @param height         pixel height of the semantic image.
     * @param rowStrideBytes bytes between consecutive rows in [labelBytes] (`>= width`).
     * @param rotationDegrees clockwise rotation to apply while writing, a multiple of 90. Use
     *                        [displayRotationToDegrees] to derive it from the current display
     *                        rotation.
     * @return a packed `width * height` `ARGB_8888` pixel array (rotated dimensions).
     */
    fun labelBufferToArgb(
        labelBytes: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        rotationDegrees: Int = 0,
    ): IntArray {
        require(width > 0 && height > 0) {
            "semantic image must be non-empty (got $width x $height)"
        }
        require(rowStrideBytes >= width) {
            "rowStrideBytes ($rowStrideBytes) must be >= width ($width)"
        }
        val rotation = normalizeRotation(rotationDegrees)
        val outWidth = rotatedWidth(width, height, rotation)
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val rowStart = y * rowStrideBytes
            for (x in 0 until width) {
                val ordinal = (labelBytes.get(rowStart + x).toInt() and 0xFF)
                    .coerceIn(0, LABEL_COUNT - 1)
                val argb = if (ordinal == UNLABELED_ORDINAL) {
                    ARGB_TRANSPARENT
                } else {
                    PALETTE_ARGB[ordinal]
                }
                // Where this sample lands once the image is turned clockwise — mirrors
                // DepthVisualization.depthBufferToArgb's indexing exactly.
                val outIndex = when (rotation) {
                    90 -> (x * outWidth) + (height - 1 - y)
                    180 -> ((height - 1 - y) * outWidth) + (width - 1 - x)
                    270 -> ((width - 1 - x) * outWidth) + y
                    else -> (y * outWidth) + x
                }
                out[outIndex] = argb
            }
        }
        return out
    }

    /**
     * Fold an arbitrary degree value into the `{0, 90, 180, 270}` quarter-turn set, rejecting
     * anything that is not a multiple of 90 — this pipeline rotates by whole quarter turns only
     * (it re-indexes pixels, it does not resample).
     */
    private fun normalizeRotation(rotationDegrees: Int): Int {
        require(rotationDegrees % 90 == 0) {
            "rotationDegrees ($rotationDegrees) must be a multiple of 90"
        }
        return ((rotationDegrees % 360) + 360) % 360
    }

    /**
     * Clamp a UI slider value to the `[0, 1]` Compose / shader-opacity contract.
     */
    fun clampUnit(value: Float): Float = when {
        value < 0f -> 0f
        value > 1f -> 1f
        else -> value
    }

    /**
     * Default fraction above which an [UNLABELED_ORDINAL]-dominant frame counts as "the scene
     * has nothing classified", not just ordinary edge noise.
     */
    const val DEFAULT_UNCLASSIFIED_THRESHOLD: Float = 0.9f

    /**
     * `true` when the current frame's single most-common label is [UNLABELED_ORDINAL] at or
     * above [threshold] — i.e. ARCore's Scene Semantics model classified almost nothing (#3274).
     *
     * The Scene Semantics model has **no indoor training data**: pointed at a living-room wall
     * it returns UNLABELED for almost every pixel, which the overlay shader paints fully
     * transparent (see `semantics_overlay.mat`) — so the demo shows exactly the plain camera
     * feed, with no on-screen indication of *why*. A user filed that as "nothing...rendered"
     * (#3274) even though the pipeline itself was working correctly end to end; the fix isn't a
     * code bug in the classification path, it's the missing feedback the #1617 principle
     * ("never leave the user staring at a screen that looks broken without saying why") demands
     * everywhere else in this codebase.
     *
     * Extracted as a pure, ARCore-free function (`topOrdinal` instead of `SemanticLabel`, so no
     * ARCore type is needed on the test classpath) so the gate can be pinned by a JVM unit test.
     * See `SemanticsOverlayTest`.
     *
     * @param topOrdinal  Ordinal (`0..11`) of the frame's highest-fraction label.
     * @param topFraction That label's fraction, in `[0, 1]`.
     * @param threshold   Minimum fraction to call it "dominant". Defaults to
     *                    [DEFAULT_UNCLASSIFIED_THRESHOLD].
     */
    fun isOutdoorSceneUnclassified(
        topOrdinal: Int,
        topFraction: Float,
        threshold: Float = DEFAULT_UNCLASSIFIED_THRESHOLD,
    ): Boolean = topOrdinal == UNLABELED_ORDINAL && topFraction >= threshold
}
