@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.theme.SceneViewTokens

data class BundledViewerModel(val assetPath: String, val displayName: String) {
    val assetName get() = assetPath.substringAfterLast('/').substringBeforeLast('.')
}

@Composable
fun ModelPickerSheet(
    models: List<BundledViewerModel>, selectedPath: String,
    surpriseAvailable: Boolean, surpriseLoading: Boolean,
    onSelect: (BundledViewerModel) -> Unit, onPark: () -> Unit,
    onSurprise: () -> Unit, onBrowse: () -> Unit, onDismiss: () -> Unit,
) {
    // Fully expanded from the start (`skipPartiallyExpanded`). The grid is a plain Column of
    // Rows rather than a LazyVerticalGrid: a lazy grid inside a sheet needs a bounded height,
    // and the `heightIn(max = …)` cap it had cut the second row's captions while the sheet
    // itself was already at full height (QA round 3). Six bundled models are three rows — the
    // whole sheet scrolls on short screens instead of the grid scrolling inside it.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = SceneViewTokens.Radius.xl, topEnd = SceneViewTokens.Radius.xl),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text("Models", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = SceneViewTokens.Space.md))
        Column(
            modifier = Modifier.fillMaxWidth().padding(SceneViewTokens.Space.md),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
        ) {
            models.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                    row.forEach { model ->
                        val selected = model.assetPath == selectedPath
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(SceneViewTokens.Radius.md))
                                .then(if (selected) Modifier.border(BorderStroke(SceneViewTokens.Layout.selectedOutlineWidth, MaterialTheme.colorScheme.primary), RoundedCornerShape(SceneViewTokens.Radius.md)) else Modifier)
                                .clickable { onSelect(model) }.padding(SceneViewTokens.Space.sm)
                        ) {
                            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(SceneViewTokens.Radius.sm)).background(MaterialTheme.colorScheme.surfaceDim), contentAlignment = Alignment.Center) {
                                ModelThumbnails.resourceFor(model.assetName)?.let { Image(painterResource(it), null, Modifier.fillMaxSize()) }
                                    ?: Icon(Icons.Outlined.ViewInAr, null)
                            }
                            Text(model.displayName, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = SceneViewTokens.Space.xs))
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        ViewerSheetRow("Park scene", "4 models", onPark)
        // "Surprise me" used to be a plain text row indistinguishable from its
        // neighbours — users reported not noticing it at all (#3324). It is the
        // one row that pulls fresh content from Sketchfab, so it gets the
        // `AutoAwesome` glyph the rest of the app already uses for "featured /
        // surprising" content (ExploreTabScreen, HomeScreen, FeaturedModelCard)
        // plus a `primary` tint — `DESIGN.md` "Bold colors: use primary … for
        // hero elements" — instead of blending into the list.
        if (surpriseAvailable) ViewerSheetRow(
            "Surprise me",
            if (surpriseLoading) "Resolving…" else "Random model from Sketchfab",
            onSurprise,
            surpriseLoading,
            icon = Icons.Filled.AutoAwesome,
        )
        ViewerSheetRow("Browse online models…", null, onBrowse)
        Spacer(Modifier.navigationBarsPadding().height(SceneViewTokens.Space.sm))
        }
    }
}

@Composable private fun ViewerSheetRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val highlighted = icon != null
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (highlighted) {
                    Modifier
                        .padding(horizontal = SceneViewTokens.Space.md, vertical = SceneViewTokens.Space.xs)
                        .clip(RoundedCornerShape(SceneViewTokens.Radius.md))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                } else Modifier
            )
            .clickable(enabled = !loading, onClick = onClick)
            .padding(SceneViewTokens.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(SceneViewTokens.Space.sm))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (highlighted) {
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                } else MaterialTheme.typography.bodyLarge,
                color = if (highlighted) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
        if (loading) CircularProgressIndicator(Modifier.size(SceneViewTokens.Layout.dockIconSize))
    }
}
