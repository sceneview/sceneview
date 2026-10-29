package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import io.github.sceneview.demo.demos.internal.ScanStageSplit
import kotlin.math.roundToInt

/**
 * The camera's pane of a capture's stage: the whole stage, or — while a room's live 3D map
 * shares it ([split]) — the band from the map's bottom down to the controls. Whatever is drawn
 * over the camera (the re-scan's frame, #4736) goes inside it, so it is projected on the
 * camera's own size and never lands on the map.
 */
internal fun Modifier.scanCameraPane(split: ScanStageSplit?): Modifier =
    if (split == null) {
        fillMaxSize()
    } else {
        fillMaxWidth()
            .offset { IntOffset(0, split.cameraTop.roundToInt()) }
            .layout { measurable, constraints ->
                val height = (split.cameraBottom - split.cameraTop).roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
    }
