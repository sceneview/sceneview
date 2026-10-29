package io.github.sceneview.node

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position

/**
 * A node that displays a text label in 3D world space.
 *
 * [TextNode] internally renders a [Bitmap] with [android.graphics.Canvas] and delegates to
 * [BillboardNode] for camera-facing behaviour. The bitmap is re-rendered whenever [text],
 * [fontSize], [textColor], [backgroundColor], or [typeface] changes.
 *
 * The label faces the camera **only** when it has a [cameraPositionProvider], and then as a full
 * look-at — front toward the camera position, top toward world `+Y`: yaw and pitch, no roll. The
 * semantics are [BillboardNode]'s, and [ViewNode]'s.
 *
 * ### Basic usage
 * ```kotlin
 * val cameraNode = rememberCameraNode(engine)
 * SceneView(cameraNode = cameraNode) {
 *     TextNode(
 *         text = "Hello 3D!",
 *         fontSize = 48f,
 *         textColor = android.graphics.Color.WHITE,
 *         backgroundColor = 0xCC000000.toInt(),
 *         widthMeters = 0.6f,
 *         heightMeters = 0.2f,
 *         cameraPositionProvider = { cameraNode.worldPosition }
 *     )
 * }
 * ```
 *
 * ### Rich text with custom typeface (node class)
 * ```kotlin
 * TextNode(
 *     materialLoader = materialLoader,
 *     text = "Bold Italic Title",
 *     fontSize = 64f,
 *     typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC),
 *     textColor = android.graphics.Color.YELLOW,
 *     backgroundColor = android.graphics.Color.TRANSPARENT,
 *     widthMeters = 1.0f,
 *     heightMeters = 0.3f,
 *     bitmapWidth = 1024,
 *     bitmapHeight = 256,
 *     cameraPositionProvider = { cameraNode.worldPosition }
 * )
 * ```
 *
 * @param materialLoader         MaterialLoader used to create the image material instance.
 * @param text                   The string to display.
 * @param fontSize               Font size in pixels used when rendering the bitmap texture.
 * @param textColor              ARGB text colour (default opaque white).
 * @param backgroundColor        ARGB background fill colour (default semi-transparent black).
 * @param typeface               [Typeface] for the text. Supports bold, italic, bold-italic, and
 *                               custom font families. Default is [Typeface.DEFAULT_BOLD].
 * @param widthMeters            Width of the quad in world-space meters.
 * @param heightMeters           Height of the quad in world-space meters.
 * @param cameraPositionProvider Initial value of [BillboardNode.cameraPositionProvider]: the camera
 *                               world position to face, read every frame. `null` (default) leaves
 *                               the orientation alone.
 * @param bitmapWidth            Resolution width of the rendered bitmap in pixels (default 512).
 * @param bitmapHeight           Resolution height of the rendered bitmap in pixels (default 128).
 */
open class TextNode(
    materialLoader: MaterialLoader,
    text: String,
    fontSize: Float = 48f,
    textColor: Int = android.graphics.Color.WHITE,
    backgroundColor: Int = 0xCC000000.toInt(),
    typeface: Typeface = Typeface.DEFAULT_BOLD,
    widthMeters: Float = 0.6f,
    heightMeters: Float = 0.2f,
    cameraPositionProvider: (() -> Position)? = null,
    val bitmapWidth: Int = 512,
    val bitmapHeight: Int = 128,
) : BillboardNode(
    materialLoader = materialLoader,
    bitmap = renderTextBitmap(
        text = text,
        fontSize = fontSize,
        textColor = textColor,
        backgroundColor = backgroundColor,
        typeface = typeface,
        bitmapWidth = bitmapWidth,
        bitmapHeight = bitmapHeight
    ),
    widthMeters = widthMeters,
    heightMeters = heightMeters,
    cameraPositionProvider = cameraPositionProvider
) {

    /** The string to display. Updating this re-renders the bitmap texture. */
    var text: String = text
        set(value) {
            if (field != value) {
                field = value
                refreshBitmap()
            }
        }

    /** Font size in pixels used when rendering the bitmap texture. */
    var fontSize: Float = fontSize
        set(value) {
            if (field != value) {
                field = value
                refreshBitmap()
            }
        }

    /** ARGB text colour. */
    var textColor: Int = textColor
        set(value) {
            if (field != value) {
                field = value
                refreshBitmap()
            }
        }

    /** ARGB background fill colour. */
    var backgroundColor: Int = backgroundColor
        set(value) {
            if (field != value) {
                field = value
                refreshBitmap()
            }
        }

    /**
     * [Typeface] for the text. Supports bold, italic, bold-italic, and custom font families.
     *
     * ```kotlin
     * textNode.typeface = Typeface.create("serif", Typeface.ITALIC)
     * ```
     */
    var typeface: Typeface = typeface
        set(value) {
            if (field != value) {
                field = value
                refreshBitmap()
            }
        }

    private fun refreshBitmap() {
        bitmap = renderTextBitmap(
            text = text,
            fontSize = fontSize,
            textColor = textColor,
            backgroundColor = backgroundColor,
            typeface = typeface,
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight
        )
    }

    companion object {
        /**
         * Renders [text] into a new [Bitmap] using Android [Canvas].
         *
         * The bitmap has a rounded-rectangle background and horizontally/vertically centred text.
         * Alpha is preserved so the material's blending mode controls transparency.
         *
         * @param text            The string to render.
         * @param fontSize        Font size in pixels.
         * @param textColor       ARGB text colour.
         * @param backgroundColor ARGB background colour.
         * @param typeface        [Typeface] for the text (supports bold, italic, custom fonts).
         * @param bitmapWidth     Bitmap resolution width in pixels.
         * @param bitmapHeight    Bitmap resolution height in pixels.
         * @return A new [Bitmap] containing the rendered text.
         */
        fun renderTextBitmap(
            text: String,
            fontSize: Float,
            textColor: Int,
            backgroundColor: Int,
            typeface: Typeface = Typeface.DEFAULT_BOLD,
            bitmapWidth: Int,
            bitmapHeight: Int
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Background with rounded corners
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = backgroundColor
                style = Paint.Style.FILL
            }
            val cornerRadius = bitmapHeight * 0.2f
            canvas.drawRoundRect(
                RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat()),
                cornerRadius,
                cornerRadius,
                bgPaint
            )

            // Centred text with configurable typeface
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textSize = fontSize
                this.typeface = typeface
                textAlign = Paint.Align.CENTER
            }
            val xPos = bitmapWidth / 2f
            val yPos = (bitmapHeight / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
            canvas.drawText(text, xPos, yPos, textPaint)

            return bitmap
        }
    }
}
