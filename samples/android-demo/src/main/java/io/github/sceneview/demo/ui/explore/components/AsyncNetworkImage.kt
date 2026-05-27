package io.github.sceneview.demo.ui.explore.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Minimal async network-image composable used by the Explore tab cards.
 *
 * Why not Coil / Glide?
 *   The demo app does not yet depend on a Compose image-loading library and
 *   we don't want to grow the APK by ~600 KB just for thumbnails (the Play
 *   Store cares about install size — every feature trades against the next).
 *   The Sketchfab gallery only ever decodes ~30 small JPEGs at a time so a
 *   one-file fetcher backed by `OkHttp` + an in-memory `LruCache` is enough.
 *
 *   When we add Coil for other reasons (e.g. animated WebP support), swap
 *   this single composable out — every call site uses the same surface
 *   (`url`, `contentDescription`, `modifier`).
 *
 * Reliability notes (#1424):
 *   - Sketchfab thumbnails can be served well above the card's render size
 *     (`preferredThumbnailUrl` falls back to the largest available image when
 *     no in-range size exists). Decoding ~30 of those full-resolution into
 *     `ARGB_8888` bitmaps blew the heap and the Explore tab crashed with
 *     `OutOfMemoryError`. The decode is now downsampled via `inSampleSize`
 *     and the cache is bounded by entry count, not raw byte size.
 *   - `OutOfMemoryError` is an `Error`, not an `Exception`, so the previous
 *     `runCatching` did NOT catch it — an OOM mid-decode took down the whole
 *     process. The fetch path now catches `Throwable` and degrades to the
 *     silent placeholder.
 *   - The shared OkHttp client now has connect/read/call timeouts so a single
 *     stalled CDN connection can't pin an IO thread (and a carousel slot)
 *     forever — that was the "very slow" symptom.
 */
@Composable
fun AsyncNetworkImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(BitmapCache[url.orEmpty()]) }
    var loading by remember(url) { mutableStateOf(bitmap == null && !url.isNullOrBlank()) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        val key = url?.takeIf { it.isNotBlank() } ?: run {
            loading = false
            return@LaunchedEffect
        }
        BitmapCache[key]?.let {
            bitmap = it
            loading = false
            failed = false
            return@LaunchedEffect
        }
        loading = true
        failed = false
        // catch Throwable (not just Exception) — a downsample miscalculation
        // could still surface an OutOfMemoryError, which must NOT crash the
        // tab. Cancellation propagates because withContext re-checks the job.
        val decoded = withContext(Dispatchers.IO) {
            @Suppress("SwallowedException") // all image-load errors degrade gracefully to null (no crash in the tab)
            try {
                fetchBitmap(key)
            } catch (oom: OutOfMemoryError) {
                null
            } catch (io: IOException) {
                null
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                null
            }
        }
        if (decoded != null) {
            BitmapCache.put(key, decoded)
            bitmap = decoded
        } else {
            failed = true
        }
        loading = false
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.5.dp,
            )
        }
        // else: silent placeholder — empty url or failed fetch (`failed`).
    }
}

/**
 * Shared OkHttp client. The Sketchfab CDN does not require auth headers.
 *
 * Timeouts are mandatory: without them a half-open connection on a flaky
 * mobile network blocks an IO thread indefinitely, which is what made the
 * Explore tab feel "very slow" and left carousels spinning forever (#1424).
 */
private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
}

/**
 * In-memory thumbnail cache bounded by entry count rather than byte size.
 *
 * A byte-size `LruCache` is a footgun here: `sizeOf` returned `byteCount`, so
 * if one decoded bitmap exceeded the cap `LruCache.put` evicted it (and every
 * other entry) immediately — thumbnails then never cached and reappeared
 * "one time in ten". Bounding by count (64 downsampled thumbnails ≈ a few MB)
 * is predictable and the downsampled decode keeps each entry small.
 */
private object BitmapCache : LruCache<String, Bitmap>(64)

/**
 * Target decode size, in pixels. Explore cards render at ~200×160 dp; even on
 * the densest phones (~3.5x) that's well under 720px, so a 720px-wide bitmap
 * is sharp while staying ~10x smaller in memory than a 2k source thumbnail.
 */
private const val TARGET_DECODE_WIDTH = 720
private const val TARGET_DECODE_HEIGHT = 720

@Throws(IOException::class)
private suspend fun fetchBitmap(url: String): Bitmap? {
    val request = Request.Builder().url(url).get().build()
    httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val bytes = response.body.bytes()
        if (bytes.isEmpty()) return null
        // Abort before the (potentially heavy) decode if the composable has
        // already left composition — avoids burning CPU on scrolled-past cards.
        coroutineContext.ensureActive()
        return decodeDownsampled(bytes)
    }
}

/**
 * Two-pass decode: first read just the bounds, then decode with an
 * `inSampleSize` that fits the result under [TARGET_DECODE_WIDTH] /
 * [TARGET_DECODE_HEIGHT]. This is the core OOM fix (#1424) — a 2048px
 * Sketchfab thumbnail used to decode straight to a ~16 MB `ARGB_8888` bitmap;
 * ~30 of those crashed the tab.
 */
private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = computeInSampleSize(
            srcWidth = bounds.outWidth,
            srcHeight = bounds.outHeight,
            reqWidth = TARGET_DECODE_WIDTH,
            reqHeight = TARGET_DECODE_HEIGHT,
        )
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/** Largest power-of-two sample size that still keeps the result ≥ requested. */
private fun computeInSampleSize(
    srcWidth: Int,
    srcHeight: Int,
    reqWidth: Int,
    reqHeight: Int,
): Int {
    var sampleSize = 1
    while (
        srcWidth / (sampleSize * 2) >= reqWidth &&
        srcHeight / (sampleSize * 2) >= reqHeight
    ) {
        sampleSize *= 2
    }
    return sampleSize
}
