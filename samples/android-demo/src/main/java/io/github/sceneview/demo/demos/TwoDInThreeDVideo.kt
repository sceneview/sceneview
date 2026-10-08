package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.demos.internal.StreamPhase
import io.github.sceneview.demo.theme.SceneViewTokens
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The clip Media plays: sixteen seconds of Big Buck Bunny, © 2008 Blender Foundation
 * (www.bigbuckbunny.org), CC BY 3.0, cut to 720p without sound. It is read from the project's
 * `assets-v1` release, never bundled: `assets/CREDITS.md` and the demo's settings sheet credit it.
 */
internal const val MEDIA_VIDEO_URL =
    "https://github.com/sceneview/sceneview/releases/download/assets-v1/big_buck_bunny_excerpt_720p.mp4"

/** A muted, looping [MediaPlayer] reading a URL, and where its stream stands. */
@Stable
internal class StreamedVideo {
    /** Null until the composition that owns it has entered; released when it leaves. */
    var player: MediaPlayer? by mutableStateOf(null)
        internal set

    var phase by mutableStateOf(StreamPhase.Loading)
        private set

    /** Prepared in time. A stream already given up on stays given up on. */
    fun ready() {
        if (phase == StreamPhase.Loading) phase = StreamPhase.Ready
    }

    /** The stream broke, or a call on its player threw: nothing plays from here on. */
    fun fail() {
        phase = StreamPhase.Failed
    }
}

/**
 * Streams [url] through a [MediaPlayer] this composition owns. `rememberMediaPlayer` reads an APK
 * asset and prepares it on the spot; a URL has to be prepared off the main thread, so the player
 * is handed to a `VideoNode` only once [StreamedVideo.phase] is [StreamPhase.Ready]. No network,
 * a broken stream or [PREPARE_TIMEOUT_MILLIS] without an answer all end in [StreamPhase.Failed].
 */
@Composable
internal fun rememberStreamedVideo(url: String): StreamedVideo {
    val context = LocalContext.current.applicationContext
    val video = remember(url) { StreamedVideo() }
    DisposableEffect(video) {
        val player = MediaPlayer()
        runCatching {
            player.setDataSource(context, Uri.parse(url))
            player.isLooping = true
            player.setVolume(0f, 0f)
            player.setOnPreparedListener { video.ready() }
            player.setOnErrorListener { _, _, _ -> video.fail(); true }
            player.prepareAsync()
        }.onFailure { video.fail() }
        video.player = player
        onDispose {
            player.setOnPreparedListener(null)
            player.setOnErrorListener(null)
            runCatching { player.release() }
        }
    }
    LaunchedEffect(video) {
        delay(PREPARE_TIMEOUT_MILLIS)
        if (video.phase == StreamPhase.Loading) video.fail()
    }
    return video
}

/**
 * What a video screen shows while it has no picture: [title], and under it the [detail] that says
 * what to do about it. A dark screen with light text in both themes, like a screen that is off.
 */
@Composable
internal fun rememberVideoStatusStill(title: String, detail: String?, widthOverHeight: Float): Bitmap {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val height = with(density) { (SceneViewTokens.Space.x4l + SceneViewTokens.Space.xl).roundToPx() }
    val margin = with(density) { SceneViewTokens.Space.lg.toPx() }
    val gap = with(density) { SceneViewTokens.Space.sm.toPx() }
    val titleSize = with(density) { typography.titleMedium.fontSize.toPx() }
    val detailSize = with(density) { typography.bodySmall.fontSize.toPx() }
    return remember(title, detail, widthOverHeight, height, margin, gap, titleSize, detailSize) {
        val width = (height * widthOverHeight).roundToInt()
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(SceneViewColors.SurfaceDim.toArgb())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = SceneViewColors.SurfaceLight.toArgb()
                textAlign = Paint.Align.CENTER
            }
            // A line wider than the screen shrinks to fit it: it is never cut.
            fun fit(text: String, size: Float, typeface: Typeface) {
                paint.typeface = typeface
                paint.textSize = size
                val measured = paint.measureText(text)
                val room = width - 2 * margin
                if (measured > room) paint.textSize = size * room / measured
            }
            fit(title, titleSize, Typeface.DEFAULT_BOLD)
            val titleMetrics = paint.fontMetrics
            val titleHeight = titleMetrics.descent - titleMetrics.ascent
            val block = if (detail == null) titleHeight else titleHeight + gap + detailSize
            val top = (height - block) / 2f
            canvas.drawText(title, width / 2f, top - titleMetrics.ascent, paint)
            if (detail != null) {
                fit(detail, detailSize, Typeface.DEFAULT)
                canvas.drawText(detail, width / 2f, top + titleHeight + gap - paint.fontMetrics.ascent, paint)
            }
        }
    }
}

private const val PREPARE_TIMEOUT_MILLIS = 15_000L
