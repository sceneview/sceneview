package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.ArDebugFraming
import io.github.sceneview.demo.demos.internal.ArDebugGeometry
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.DebugGroup
import io.github.sceneview.demo.demos.internal.OrbitBand
import io.github.sceneview.demo.theme.themedStageChrome
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A second view of the same captured surface. Its heading is fixed, so the user's turn does
 * not spin the model. Only its bounds grow. The untinted snapshot is rebuilt on a worker at
 * most twice a second; ARCore and the full-screen AR layer remain the capture's only clock.
 * The same view expands while saving, keeping the captured place visible until Photo opens.
 */
@Composable
internal fun ScanModelPreview(
    session: ArDebugSession,
    media: RerunReplayMedia,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    saving: Boolean,
    modifier: Modifier = Modifier,
) {
    val snapshot by produceState<ReplayDenseLayer?>(null, media) {
        var previous: ReplayDenseLayer? = null
        while (true) {
            val next = media.liveDense?.invoke() ?: media.dense
            if (next != null && next !== previous) {
                value = withContext(Dispatchers.Default) { ReplayDenseLayer.of(next.cloud, next.voxelM) }
                previous = next
            }
            delay(600)
        }
    }
    val current by rememberUpdatedState(snapshot)
    val bounds = remember(snapshot) { snapshot?.whole?.let { ArDebugGeometry.robustBounds(it) } }
    val currentBounds by rememberUpdatedState(bounds)
    // Never a blank placeholder card: the camera owns the screen until a surface exists.
    if (bounds == null) return
    val orbit = remember(media) { ArDebugOrbitCamera(drift = false, band = OrbitBand.CARD) }
    val drawn = remember(media) { media.withLiveDense(overCamera = { false }) { current } }
    val chrome = themedStageChrome()
    val previewHeight = (LocalConfiguration.current.screenHeightDp * 0.23f).dp
    val placement = if (saving) modifier.fillMaxSize() else modifier
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(start = 16.dp, end = 16.dp, bottom = 140.dp)
        .fillMaxWidth().height(previewHeight)
    Box(placement.clip(RoundedCornerShape(if (saving) 0.dp else 24.dp)).background(chrome.ground)
        .testTag("scan_live_model")) {
        CompositionLocalProvider(LocalStageChrome provides chrome) {
        ArDebugSceneView(
            session = session,
            orbit = orbit,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            replay = drawn,
            modifier = Modifier.fillMaxSize(),
            compact = true,
            measuresRoom = false,
            visible = { it == DebugGroup.Points },
            drivesSession = false,
            follow = {
                ArDebugFraming.home(
                    currentBounds, ArDebugFraming.HOME_AZIMUTH, orbit.verticalFovDegrees,
                    orbit.aspect, elevationDegrees = 45f, band = OrbitBand.CARD, fill = 0.85f,
                )
            },
        )
        }
        if (!saving) Text(
            stringResource(R.string.scan_capture_live_model),
            color = chrome.onGlass,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )
    }
}
