@file:OptIn(ExperimentalSceneViewApi::class)

package io.github.sceneview.demo.demos

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LocalDemoBottomChromeCover
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Radius
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.rememberEngine
import io.github.sceneview.snapshot.ModelSnapshot
import io.github.sceneview.snapshot.ModelSnapshotOptions
import io.github.sceneview.snapshot.ModelThumbnailState
import io.github.sceneview.snapshot.rememberModelSnapshot
import io.github.sceneview.snapshot.rememberModelThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val TAG = "ModelThumbnailsDemo"

/**
 * The models the demo ships, as a library would list them: one thumbnail each, per angle. The
 * night circuit is left out: it is a whole set, with quantized positions (see [ModelSnapshot]).
 */
private val THUMBNAIL_MODELS = listOf(
    "models/khronos_damaged_helmet.glb" to "Damaged Helmet",
    "models/khronos_glam_velvet_sofa.glb" to "Velvet Sofa",
    "models/khronos_sheen_chair.glb" to "Sheen Chair",
    "models/khronos_iridescent_dish.glb" to "Olive Dish",
    "models/khronos_lantern.glb" to "Lantern",
    "models/khronos_toy_car.glb" to "Toy Car",
    "models/quaternius_ranger.glb" to "Ranger",
    "models/khronos_fox.glb" to "Fox",
    "models/shiba.glb" to "Shiba",
)

/** The two angles of the grid: the default three-quarter view, and the same turned around. */
private val FROM_BEHIND = ModelSnapshotOptions(yawDegrees = 210f)

/** Thumbnail size: rendered at the card's size on a phone, so the pixels are the screen's. */
private val THUMBNAIL_SIZE = 160.dp

/**
 * Model thumbnails: a library grid whose pictures come from [ModelSnapshot], rendered off screen
 * one after the other on the screen's engine — no `SceneView` on this screen at all.
 *
 * All eighteen cells ask at once; the snapshot queues them and renders one model at a time. Each
 * costs the main thread what loading that model into a scene costs, so the grid drops frames while
 * it fills: the status line reports the total, the time per thumbnail and the longest UI frame
 * seen meanwhile. A cell whose model cannot be thumbnailed shows a broken-image icon.
 */
@Composable
fun ModelThumbnailsDemo(onBack: () -> Unit) {
    DemoScaffold(
        title = stringResource(R.string.demo_model_thumbnails_title),
        onBack = onBack,
        dockHidden = true,
        themedStage = true,
        hasScene = false,
    ) {
        val engine = rememberEngine()
        val snapshot = rememberModelSnapshot(engine)
        ThumbnailLibrary(snapshot)
    }
}

@Composable
private fun ThumbnailLibrary(snapshot: ModelSnapshot) {
    val total = THUMBNAIL_MODELS.size * 2
    val start = remember { SystemClock.elapsedRealtime() }
    var done by remember { mutableIntStateOf(0) }
    var failed by remember { mutableIntStateOf(0) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var longestFrameMs by remember { mutableLongStateOf(0L) }
    var finished by remember { mutableStateOf(false) }

    // The longest frame the UI took while the thumbnails rendered: the figure that says whether
    // the queue kept out of the way.
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (!finished) {
            val now = withFrameNanos { it }
            longestFrameMs = maxOf(longestFrameMs, (now - last) / 1_000_000)
            last = now
        }
    }

    val onResult: (ok: Boolean) -> Unit = { ok ->
        if (ok) done++ else failed++
        Log.d(TAG, "${done + failed}/$total ${if (ok) "ready" else "failed"} at ${SystemClock.elapsedRealtime() - start} ms")
        if (done + failed == total) {
            elapsedMs = SystemClock.elapsedRealtime() - start
            finished = true
            Log.i(
                TAG,
                "Rendered $done/$total thumbnails in $elapsedMs ms (${elapsedMs / total} ms each), " +
                    "longest UI frame $longestFrameMs ms",
            )
        }
    }

    val dock = LocalDemoBottomChromeCover.current + Space.md
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = SceneViewTokens.Layout.touchTarget + Space.md * 2)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.md)
            .padding(bottom = dock),
        verticalArrangement = Arrangement.spacedBy(Space.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = SceneViewTokens.Home.rowMinWidth * 2),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Text(
                text = stringResource(R.string.demo_model_thumbnails_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when {
                    !finished -> stringResource(R.string.demo_model_thumbnails_progress, done + failed, total)
                    failed > 0 -> stringResource(R.string.demo_model_thumbnails_failed, failed, total)
                    else -> stringResource(
                        R.string.demo_model_thumbnails_done,
                        done,
                        String.format(Locale.ROOT, "%.1f s", elapsedMs / 1000f),
                        elapsedMs / total,
                        longestFrameMs,
                    )
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ThumbnailSection(
                title = stringResource(R.string.demo_model_thumbnails_front),
                snapshot = snapshot,
                options = ModelSnapshotOptions(),
                variant = "front",
                onResult = onResult,
            )
            ThumbnailSection(
                title = stringResource(R.string.demo_model_thumbnails_back),
                snapshot = snapshot,
                options = FROM_BEHIND,
                variant = "back",
                onResult = onResult,
            )
        }
    }
}

@Composable
private fun ThumbnailSection(
    title: String,
    snapshot: ModelSnapshot,
    options: ModelSnapshotOptions,
    variant: String,
    onResult: (Boolean) -> Unit,
) {
    Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    // A plain column of rows rather than a lazy grid: every cell is composed at once, so all of
    // them queue on the snapshot from the first frame — the case the queue is made for.
    THUMBNAIL_MODELS.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            row.forEach { (path, name) ->
                ThumbnailCard(
                    snapshot = snapshot,
                    path = path,
                    name = name,
                    options = options,
                    variant = variant,
                    onResult = onResult,
                    modifier = Modifier.weight(1f),
                )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ThumbnailCard(
    snapshot: ModelSnapshot,
    path: String,
    name: String,
    options: ModelSnapshotOptions,
    variant: String,
    onResult: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val thumbnail = rememberModelThumbnail(snapshot, path, size = THUMBNAIL_SIZE, options = options)
    // Loading, then Ready or Failed for good: each card reports its one result.
    LaunchedEffect(thumbnail) {
        when (thumbnail) {
            is ModelThumbnailState.Ready -> {
                onResult(true)
                if (BuildConfig.DEBUG) saveForInspection(context, thumbnail.bitmap, name, variant)
            }
            is ModelThumbnailState.Failed -> onResult(false)
            ModelThumbnailState.Loading -> Unit
        }
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                when (thumbnail) {
                    is ModelThumbnailState.Ready -> Image(
                        bitmap = thumbnail.bitmap,
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    is ModelThumbnailState.Failed -> Icon(
                        imageVector = Icons.Filled.BrokenImage,
                        contentDescription = stringResource(R.string.demo_model_thumbnails_cell_failed, name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ModelThumbnailState.Loading ->
                        CircularProgressIndicator(modifier = Modifier.size(Space.lg), strokeWidth = 2.dp)
                }
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Debug builds keep each thumbnail as a PNG in the app's cache, to pull and inspect the alpha. */
private suspend fun saveForInspection(context: Context, thumbnail: ImageBitmap, name: String, variant: String) {
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "model-thumbnails").apply { mkdirs() }
            val file = File(dir, "${name.lowercase(Locale.ROOT).replace(' ', '-')}-$variant.png")
            file.outputStream().use { thumbnail.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { Log.w(TAG, "Could not save $name ($variant)", it) }
    }
}
