package io.github.sceneview.demo.demos

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RescanAlignment
import io.github.sceneview.demo.demos.internal.RescanGuidance
import io.github.sceneview.demo.demos.internal.RescanMerge
import io.github.sceneview.demo.demos.internal.RescanPose
import io.github.sceneview.demo.demos.internal.RescanReference
import io.github.sceneview.demo.demos.internal.RescanRefusal
import io.github.sceneview.demo.demos.internal.RescanRegistrar
import io.github.sceneview.demo.demos.internal.RescanZone
import io.github.sceneview.demo.demos.internal.ScanLens
import io.github.sceneview.demo.demos.internal.SvpcCodec
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.overMediaEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The re-scan guide's status line over the live view (#4736). */
internal const val RESCAN_GUIDE_TAG = "rescan_guide"

/** The part's amber frame drawn over the camera, once the re-scan is lined up. */
internal const val RESCAN_GUIDE_FRAME_TAG = "rescan_guide_frame"

/** A part picked to scan again (#4736): [zone], among the scan's [zones], and the scan's [cloud] to line up on. */
internal class RescanPick(val zone: RescanZone, val zones: List<RescanZone>, val cloud: DenseCloud)

/** Where a live re-scan stands against the scan it completes. */
internal sealed interface RescanStatus {
    /** Not lined up yet: too little of what the scan saw is in view. */
    data object LiningUp : RescanStatus

    /** Lined up: [pose] takes the re-scan into the scan's frame, and [fill] of the part has points. */
    class Lined(val pose: RescanPose, val fill: Float) : RescanStatus

    /** Saved, and refused: lining it up came to [why], nothing is merged. */
    class Refused(val why: RescanRefusal?) : RescanStatus
}

/** What saving a re-scan came to. */
internal sealed interface RescanSave {
    /** [pack]: the scan's capture with the re-scan's points added, to keep as a new scan. */
    class Merged(val pack: RerunCapturePack) : RescanSave

    /** Not lined up ([why], `null` when the re-scan had no dense cloud to line up): nothing to keep. */
    class Refused(val why: RescanRefusal?) : RescanSave
}

/**
 * A re-scan of [pick]'s part of the scan [pack] holds (#4736), saved as a new scan named
 * [mergedTitle] — or, when it does not line up, on its own as [partTitle] (#4815). The scan is
 * read for lining up once, off the main thread, on first use.
 */
@Stable
internal class RescanTarget(
    val pick: RescanPick,
    val pack: RerunCapturePack,
    val mergedTitle: String,
    val partTitle: String = mergedTitle,
) {
    private val reference: RescanReference? by lazy { RescanReference.of(pick.cloud) }

    /** The parts the re-scan may fill in: its points there say nothing against the scan. */
    private val open: List<FloatArray> = pick.zones.map { it.box }

    /** Where the live re-scan stands; written off the main thread, read by the guide. */
    var status: RescanStatus by mutableStateOf(RescanStatus.LiningUp)
        private set

    /**
     * Lines the live re-scan [live] up and measures how much of the part it fills. A later attempt
     * that does not line up keeps the last pose: one AR session moves rigidly against the scan.
     * Off the main thread.
     */
    fun follow(live: DenseCloud) {
        val scan = reference ?: return
        val pose = when (val found = RescanRegistrar.register(scan, live, open)) {
            is RescanAlignment.Lined -> found.pose
            is RescanAlignment.Refused -> (status as? RescanStatus.Lined)?.pose ?: return
        }
        status = RescanStatus.Lined(pose, RescanGuidance.fill(pick.zone.kind, pick.zone.box, pose.moved(live)))
    }

    /**
     * The saved re-scan [rescan] lined up on the scan and merged into a copy of its capture, or
     * refused — never merged wrong. Off the main thread.
     */
    fun merge(rescan: RerunCapturePack): RescanSave {
        val result = mergeOf(rescan)
        if (result is RescanSave.Refused) status = RescanStatus.Refused(result.why)
        Log.i(TAG, "merge: ${if (result is RescanSave.Refused) "refused ${result.why}" else "merged"}")
        return result
    }

    private fun mergeOf(rescan: RerunCapturePack): RescanSave {
        val scan = reference ?: return RescanSave.Refused(RescanRefusal.NoNormals)
        val cloud = denseOf(rescan) ?: return RescanSave.Refused(null)
        val found = RescanRegistrar.register(scan, cloud, open)
        if (found is RescanAlignment.Refused) return RescanSave.Refused(found.why)
        val pose = (found as RescanAlignment.Lined).pose
        Log.i(TAG, "re-scan $found")
        val merged = RescanMerge.cloud(pick.cloud, cloud, pose, open)
        return RescanMerge.pack(pack, merged)?.let { RescanSave.Merged(it) } ?: RescanSave.Refused(null)
    }

    /** [pack]'s dense cloud, read back from its media archive. */
    private fun denseOf(pack: RerunCapturePack): DenseCloud? {
        val opened = pack.open() ?: return null
        val dense = opened.manifest.dense ?: return null
        return opened.bytesOf(dense.path)?.let { SvpcCodec.decode(it) }?.takeIf { it.count > 0 }
    }
}

/**
 * Over the live view of a re-scan (#4736): the part to fill in, framed in amber on the camera
 * where it stands once the re-scan is lined up, and a line saying how far along it is. [live]
 * is the live cloud, read on the main thread every [FOLLOW_MS]; [lens] the phone's eye, drawn
 * from while [drawn] (the live view follows the phone).
 */
@Composable
internal fun RescanGuide(
    target: RescanTarget,
    live: () -> DenseCloud?,
    lens: ScanLens,
    drawn: Boolean,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(target) {
        while (true) {
            delay(FOLLOW_MS)
            val cloud = live()?.takeIf { it.count > 0 } ?: continue
            withContext(Dispatchers.Default) {
                runCatching { target.follow(cloud) }.onFailure { Log.w(TAG, "lining up failed", it) }
            }
        }
    }
    // The eye is not snapshot state: a frame tick copies its version, which the frame reads to redraw.
    var eye by remember(lens) { mutableLongStateOf(lens.version) }
    LaunchedEffect(lens) { while (true) withFrameNanos { eye = lens.version } }
    val status = target.status
    val amber = SceneViewTokens.ArOverlay.accentGuidance
    Box(modifier.fillMaxSize()) {
        val lined = status as? RescanStatus.Lined
        if (lined != null && drawn) {
            val corners = remember(lined.pose, target) { lined.pose.inverse().corners(target.pick.zone.box) }
            Canvas(Modifier.fillMaxSize().testTag(RESCAN_GUIDE_FRAME_TAG)) {
                if (eye == 0L || !lens.ready) return@Canvas
                val edges = RescanGuidance.outline(corners, lens.cameraToWorld, lens.projection, size.width, size.height)
                val stroke = FRAME_STROKE.toPx()
                for (k in edges.indices step EDGE_FLOATS) {
                    drawLine(amber, Offset(edges[k], edges[k + 1]), Offset(edges[k + 2], edges[k + 3]), stroke, StrokeCap.Round)
                }
            }
        }
        RescanGuideLine(
            text = when {
                status is RescanStatus.Refused -> stringResource(R.string.scan_rescan_refused)
                lined == null -> stringResource(R.string.scan_rescan_lining_up)
                RescanGuidance.covered(lined.fill) -> stringResource(R.string.scan_rescan_covered)
                else -> stringResource(R.string.scan_rescan_fill, (lined.fill * PERCENT).roundToInt())
            },
            modifier = Modifier.align(BiasAlignment(0f, LINE_BIAS)),
        )
    }
}

/** The guide's line, a card on the stage like the result's pills. */
@Composable
private fun RescanGuideLine(text: String, modifier: Modifier = Modifier) {
    val chrome = LocalStageChrome.current
    val shape = RoundedCornerShape(SceneViewTokens.Radius.full)
    Text(
        text = text,
        style = SceneViewTokens.Type.caption,
        color = chrome.onCard,
        textAlign = TextAlign.Center,
        modifier = modifier
            .padding(horizontal = Space.lg)
            .widthIn(max = LINE_MAX_WIDTH)
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .clip(shape)
            .background(chrome.card, shape)
            .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
            .padding(horizontal = Space.md, vertical = Space.sm)
            .testTag(RESCAN_GUIDE_TAG)
            .semantics {
                contentDescription = text
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/** How often the live re-scan is lined up again, ms: a few hundred ms of work each time. */
private const val FOLLOW_MS = 1_500L

/** The line's place, from the top (−1) to the bottom (1): above the middle, clear of the HUD and the cards. */
private const val LINE_BIAS = -0.35f
private val LINE_MAX_WIDTH = 360.dp
private val FRAME_STROKE = 3.dp
private const val EDGE_FLOATS = 4
private const val PERCENT = 100f
private const val TAG = "ScanRescan"
