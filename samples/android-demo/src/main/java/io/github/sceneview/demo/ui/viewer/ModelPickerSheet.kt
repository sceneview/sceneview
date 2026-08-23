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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
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
        if (surpriseAvailable) ViewerSheetRow("Surprise me", if (surpriseLoading) "Resolving…" else null, onSurprise, surpriseLoading)
        ViewerSheetRow("Browse online models…", null, onBrowse)
        Spacer(Modifier.navigationBarsPadding().height(SceneViewTokens.Space.sm))
        }
    }
}

@Composable private fun ViewerSheetRow(title: String, subtitle: String?, onClick: () -> Unit, loading: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable(enabled = !loading, onClick = onClick).padding(SceneViewTokens.Space.md), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall) } }
        if (loading) CircularProgressIndicator(Modifier.size(SceneViewTokens.Layout.dockIconSize))
    }
}
