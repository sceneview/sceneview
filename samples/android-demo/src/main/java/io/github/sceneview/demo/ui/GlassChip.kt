package io.github.sceneview.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * A selectable capsule over the scene — the white-on-media language of the dock, readable on the
 * dark stage in both themes.
 *
 * [swatch] is the colour of the thing the chip stands for; [icon] labels a mode. [toggle] picks
 * the semantics: a switch that is on or off, or one radio button of a group. [style] picks how
 * the selected state looks — see [GlassChipStyle].
 */
@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    toggle: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GlassChipStyle = GlassChipStyle.Solid,
    swatch: Color? = null,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.full)
    val solid = style == GlassChipStyle.Solid && selected
    val struck = style == GlassChipStyle.Legend && !selected
    val content = when {
        solid -> SceneViewTokens.Stage.background
        struck -> SceneViewTokens.Glass.onGlassMuted
        else -> SceneViewTokens.Glass.onGlass
    }
    Row(
        modifier = modifier
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .overMediaEdge(shape)
            .clip(shape)
            .background(if (solid) SceneViewTokens.Glass.onGlass else SceneViewTokens.Glass.surface)
            .then(
                if (toggle) {
                    Modifier.toggleable(value = selected, role = Role.Switch, onValueChange = { onClick() })
                } else {
                    Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                },
            )
            .padding(horizontal = SceneViewTokens.Glass.pillPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
    ) {
        when {
            solid && swatch != null -> Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
            )
            swatch != null -> GlassChipSwatch(color = swatch, hollow = struck)
            icon != null -> Icon(
                icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            textDecoration = if (struck) TextDecoration.LineThrough else null,
            maxLines = 1,
        )
    }
}

/** The colour dot of a [GlassChip]: filled, or a ring of the muted foreground when [hollow]. */
@Composable
private fun GlassChipSwatch(color: Color, hollow: Boolean) {
    Box(
        Modifier
            .size(SceneViewTokens.Space.md)
            .clip(CircleShape)
            .then(
                if (hollow) {
                    Modifier.border(
                        SceneViewTokens.Glass.borderWidth * 2,
                        SceneViewTokens.Glass.onGlassMuted,
                        CircleShape,
                    )
                } else {
                    Modifier.background(color)
                },
            ),
    )
}
