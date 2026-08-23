package io.github.sceneview.demo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
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
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(SceneViewTokens.Radius.full),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(SceneViewTokens.Glass.surface)
            .border(
                BorderStroke(SceneViewTokens.Glass.borderWidth, SceneViewTokens.Glass.border),
                shape,
            ),
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
                .height(SceneViewTokens.Glass.pillHeight)
                .padding(horizontal = SceneViewTokens.Glass.pillPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
