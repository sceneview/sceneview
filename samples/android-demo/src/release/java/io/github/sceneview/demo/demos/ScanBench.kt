package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugFrame

/** Release builds collect no bench observations. */
@Suppress("UNUSED_PARAMETER")
internal fun scanBenchObserve(
    media: RerunReplayMedia,
    frame: ArDebugFrame,
    coverage: ScanCoverageState,
    dense: ReplayDenseLayer?,
    walls: ScanWallsState?,
) = Unit
