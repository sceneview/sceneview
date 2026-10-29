package io.github.sceneview.demo.demos.internal.semantic

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.ScanSurface
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * No 2D detector — not on the device yet, or not usable. A recognition with it reads no photo and
 * lifts nothing, so all it finds are the walls' doors and windows, which come from the dense cloud.
 */
object NoDetector : SceneDetector {
    override val name = "none"
    override fun detect(image: SceneImage) = emptyList<Detection2D>()
}

/**
 * A scan recognised in two passes, so that what needs no detector does not wait for it. The first
 * pass runs at once with [NoDetector] and no photo read: the doors and windows. The second runs
 * once [loadDetector] has the detector — a 10 MB download the first time, which may take minutes
 * or never end — and finds everything, the furniture included, with the first pass's openings
 * kept as they were. [loadDetector] starts before the first pass and runs alongside it; `null`
 * from it means no second pass.
 *
 * [recognize] runs a pass with the recognizer it is given (`null` when the pass failed); [onPass]
 * hears each pass that came out, in order. The detector is closed once its pass is done, or when
 * the caller is cancelled.
 */
suspend fun recognizeInPasses(
    loadDetector: suspend () -> SceneDetector?,
    recognize: suspend (SceneRecognizer) -> SceneRecognition?,
    onPass: (SceneRecognition) -> Unit,
    openings: WallOpenings = CloudWallOpenings(),
) {
    var loaded: SceneDetector? = null
    try {
        coroutineScope {
            val loading = launch(start = CoroutineStart.UNDISPATCHED) { loaded = loadDetector() }
            val first = recognize(SceneRecognizer(NoDetector, openings, maxFrames = 0))
            first?.let(onPass)
            loading.join()
            val detector = loaded ?: return@coroutineScope
            val kept = if (first != null) KeptOpenings(first.openings, first.openingsNote) else openings
            recognize(SceneRecognizer(detector, kept))?.let(onPass)
        }
    } finally {
        loaded?.close()
    }
}

/** The first pass's openings, kept as they were for the second, with what they were found on. */
private class KeptOpenings(private val openings: List<WallOpening>, private val note: String) : WallOpenings {
    override fun find(cloud: DenseCloud, surfaceOf: (Int) -> ScanSurface) = openings
    override fun survey(cloud: DenseCloud, surfaceOf: (Int) -> ScanSurface) = WallSurvey(openings, note)
}

/**
 * One pass of [recognizeInPasses] as the replay logs it. The openings pass runs with [NoDetector]
 * and reads no photo — its "0 keyframes" is by design —, so it says what the walls gave, and why
 * none when there are none; the detector's pass says what it found on how many photos.
 */
fun recognitionLogLine(recognition: SceneRecognition): String =
    if (recognition.detector == NoDetector.name) {
        "wall openings (from the dense cloud, no photo read): ${recognition.openingsNote} " +
            "in ${recognition.totalMillis} ms"
    } else {
        "recognised ${recognition.items.size} parts on ${recognition.frames.size} keyframes " +
            "with ${recognition.detector} in ${recognition.totalMillis} ms, " +
            "${recognition.openings.size} wall openings among them before the furniture's precedence"
    }
