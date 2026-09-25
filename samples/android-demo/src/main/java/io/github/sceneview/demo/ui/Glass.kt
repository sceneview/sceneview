package io.github.sceneview.demo.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * Liquid-glass chrome for surfaces that float over a live 3D / AR viewport
 * (`DESIGN.md` "Liquid Glass", button-glass row; `final-spec.md` §3).
 *
 * Theme-independent by design: the scene underneath is media, so the chrome is
 * white-on-media in both light and dark. There is deliberately **no blur** — the
 * viewport is a `SurfaceView`, which a Compose render effect cannot sample, so a
 * blur would read as a grey smear over black. An 8 % white fill plus a 1 dp 8 %
 * white border is what survives that constraint.
 *
 * Every colour and size here is a [SceneViewTokens] token.
 */
/**
 * `over-media-edge` — the two-band boundary of a control that floats over live media.
 *
 * WCAG 1.4.11 asks 3:1 for the visual information needed to identify a component. A
 * single white line cannot deliver that over a camera frame, because the frame is not a
 * colour we chose: 36 % white is 1.4:1 on a white wall, and 75 % black is 1.5:1 on a night
 * scene. Two adjacent bands can, because the room can only lose to one of them at a time —
 * the white ring carries the dark grounds, the black halo carries the bright ones.
 *
 * Both are painted **outside** the element's fill, which is the other half of the fix.
 * `Modifier.border` strokes inside the bounds, over the element's own 14 % white glass:
 * white-on-glass is 1.03:1, so the old border was invisible by construction whatever its
 * opacity. Here the ring straddles the boundary (half on the fill, half on the media) and
 * the halo sits entirely on the media, 1 dp further out.
 *
 * Apply it **before** any `clip`/`background` in the chain — it draws past the layout
 * bounds on purpose, and a clip earlier in the chain would cut the halo off:
 *
 * ```
 * Modifier.overMediaEdge(shape).clip(shape).background(SceneViewTokens.Glass.surface)
 * ```
 */
fun Modifier.overMediaEdge(shape: Shape): Modifier = drawWithContent {
    drawContent()
    val band = SceneViewTokens.Glass.edgeWidth.toPx()
    drawOutline(
        outline = shape.createOutline(size, layoutDirection, this),
        color = SceneViewTokens.Glass.edgeRing,
        style = Stroke(width = band),
    )
    translate(left = -band, top = -band) {
        drawOutline(
            outline = shape.createOutline(
                Size(size.width + 2 * band, size.height + 2 * band),
                layoutDirection,
                this,
            ),
            color = SceneViewTokens.Glass.edgeHalo,
            style = Stroke(width = band),
        )
    }
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(SceneViewTokens.Radius.full),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .overMediaEdge(shape)
            .clip(shape)
            .background(SceneViewTokens.Glass.surface),
        // Centred, not the Box default of top-start (#3835). A caller that raises the
        // surface's minimum size — `GlassActionPill` lifts a 36 dp pill to the 48 dp
        // touch target — got its content pinned to the top 36 dp, 12 dp off the
        // pill's vertical centre. A wrap-content surface is unaffected.
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides SceneViewTokens.Glass.onGlass) {
            content()
        }
    }
}

/**
 * 44 dp glass circle carrying a white icon, inside a 48 dp touch target.
 *
 * [contentDescription] is applied to the clickable node so accessibility and UI
 * automation (Maestro `tapOn: "Navigate back"`) see one button with one label.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) SceneViewTokens.Motion.pressScale else 1f,
        animationSpec = SceneViewTokens.Motion.spring(),
        label = "glass-press",
    )
    Box(
        modifier = modifier
            .size(SceneViewTokens.Layout.touchTarget)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = SceneViewTokens.Glass.iconButtonSize / 2),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            modifier = Modifier
                .size(SceneViewTokens.Glass.iconButtonSize)
                .graphicsLayer { scaleX = scale; scaleY = scale },
            shape = CircleShape,
        ) {
            Box(Modifier.size(SceneViewTokens.Glass.iconButtonSize), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = SceneViewTokens.Glass.onGlass.copy(alpha = if (enabled) 1f else 0.38f),
                )
            }
        }
    }
}

/**
 * 36 dp tall glass pill with 14 dp horizontal padding — the identity pill and
 * any other short, read-only label floating over the scene.
 */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    GlassSurface(modifier = modifier) {
        Row(
            modifier = Modifier
                .heightIn(min = SceneViewTokens.Glass.pillHeight)
                .padding(horizontal = SceneViewTokens.Glass.pillPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * A [GlassPill] that is a button: glyph + one short label, with the same press
 * spring and the same white-on-media treatment as [GlassIconButton].
 *
 * The pill — not a dock item — is the shape for an action that must stay
 * visible over the scene without claiming one of the four labelled dock slots
 * (`DESIGN.md` "Floating Dock": at most four items plus the accent). [icon] is
 * replaced by an indeterminate spinner while [loading], so the pill neither
 * resizes nor greys out while the action resolves.
 *
 * [contentDescription] is the accessible name and defaults to [label]; pass the
 * longer phrase when the visible label is a shortened one.
 */
@Composable
fun GlassActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    contentDescription: String = label,
) {
    val accessibleName = contentDescription
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) SceneViewTokens.Motion.pressScale else 1f,
        animationSpec = SceneViewTokens.Motion.spring(),
        label = "glass-pill-press",
    )
    GlassPill(
        modifier = modifier
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            // Clipped BEFORE the click, so the ripple follows the capsule instead
            // of painting the square bounds `GlassSurface` clips a step later.
            .clip(RoundedCornerShape(SceneViewTokens.Radius.full))
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = accessibleName },
    ) {
        Box(
            modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
                    color = SceneViewTokens.Glass.onGlass,
                    strokeWidth = SceneViewTokens.Glass.borderWidth * 2,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
                    tint = SceneViewTokens.Glass.onGlass,
                )
            }
        }
        Spacer(Modifier.size(SceneViewTokens.Space.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = SceneViewTokens.Glass.onGlass,
            maxLines = 1,
        )
    }
}
