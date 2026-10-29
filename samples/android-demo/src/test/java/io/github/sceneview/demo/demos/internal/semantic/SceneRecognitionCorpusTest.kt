package io.github.sceneview.demo.demos.internal.semantic

import io.github.sceneview.demo.demos.RerunCaptureBuilder
import io.github.sceneview.demo.demos.ScanPhoto
import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.OpenedCapture
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.RerunRrdReader
import io.github.sceneview.demo.demos.internal.RerunScanFile
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.ScanDevice
import io.github.sceneview.demo.demos.internal.ScanObjects
import io.github.sceneview.demo.demos.internal.SvpcCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.channels.FileChannel

/**
 * Recognition run on real recordings, off the phone: every `.svscan` and `.rrd` under
 * `SCENEVIEW_SCAN_CORPUS` is opened with the demo's own readers, recognised with the app's
 * detector, and written under `SCENEVIEW_SCAN_OUT/<scan>/`: `recognized.json` (what was found: label,
 * box, confidence, views; and per keyframe its detections and timings), one annotated
 * `keyframe-NN.png` per keyframe, and `timings.txt`. `SCENEVIEW_SCAN_OUT/summary.txt` names the
 * detector the run used, then gives one line per scan: what was found, or why it was skipped (not a
 * readable scan, no dense cloud). Skipped when either variable is unset.
 *
 * ```
 * SCENEVIEW_SCAN_CORPUS=~/scans SCENEVIEW_SCAN_OUT=/tmp/recognized \
 *   ./gradlew :samples:android-demo:testDebugUnitTest --tests '*SceneRecognitionCorpusTest*'
 * ```
 *
 * The detector is the `.onnx` file `SCENEVIEW_SCENE_MODEL` names, else YOLOX-Tiny's ONNX export —
 * the one the app's LiteRT model was converted from ([OnnxSceneDetector]) —, downloaded once into
 * `<java.io.tmpdir>/sceneview-scene-detector/` and checked as the app checks its own. A path there
 * that is no file stops the run instead of dropping to no detector. With
 * `SCENEVIEW_SCENE_MODEL=none`, or without network, the scans are still recognised for their doors
 * and windows, which need no detector — what a phone shows while it downloads one — and the photo
 * run is skipped. CI sets none of these variables, so it never downloads anything. `SCENEVIEW_SCENE_PHOTOS` runs the models on a
 * folder of plain photos instead, for the found / missed / time-per-photo table.
 */
class SceneRecognitionCorpusTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun realScans() {
        val corpus = System.getenv(CORPUS)?.let(::File)
        val out = System.getenv(OUT)?.let(::File)
        assumeTrue("$CORPUS and $OUT unset: no corpus run", corpus != null && out != null)
        val scans = scansUnder(corpus!!)
        assumeTrue("no .svscan or .rrd under $corpus", scans.isNotEmpty())
        recognizeAll(scans, out!!, models(System.getenv(MODEL)).firstOrNull())
    }

    @Test
    fun photos() {
        val photos = System.getenv(PHOTOS)?.let(::File)
        val out = System.getenv(OUT)?.let(::File)
        assumeTrue("$PHOTOS and $OUT unset: no photo run", photos != null && out != null)
        val files = photos!!.listFiles { f -> f.extension.lowercase() in IMAGES }.orEmpty().sorted()
        assumeTrue("no photos under $photos", files.isNotEmpty())
        val models = models(System.getenv(MODEL))
        assumeTrue("no detector: set $MODEL, or run with network to download it", models.isNotEmpty())
        for ((name, model) in models) {
            val dir = File(out, "photos-$name").apply { mkdirs() }
            val table = StringBuilder("photo\twidth\theight\tdetect_ms\tfound\n")
            val sheet = ArrayList<SceneImage>()
            OnnxSceneDetector(mapped(model), name).use { detector ->
                // The first run pays the session's warm-up: timed apart, not in the table.
                val warm = System.nanoTime()
                detector.detect(SceneImage(WARM_UP, WARM_UP, IntArray(WARM_UP * WARM_UP)))
                table.append("# warm-up\t\t\t${(System.nanoTime() - warm) / NANOS_PER_MS}\t\n")
                var total = 0L
                for (file in files) {
                    val image = SceneTestKit.sceneImage(file.readBytes()) ?: continue
                    val t0 = System.nanoTime()
                    val found = detector.detect(image)
                    val ms = (System.nanoTime() - t0) / NANOS_PER_MS
                    total += ms
                    table.append("${file.name}\t${image.width}\t${image.height}\t$ms\t")
                        .append(found.joinToString(" ") { "${it.name}:%.2f".format(it.score) }).append('\n')
                    val annotated = SceneTestKit.annotate(image, found)
                    File(dir, "${file.nameWithoutExtension}.png").writeBytes(SceneTestKit.png(annotated))
                    sheet += annotated
                }
                table.append("# total\t\t\t$total\t\n")
            }
            File(dir, "timings.tsv").writeText(table.toString())
            File(dir, "contact-sheet.png").writeBytes(SceneTestKit.png(SceneTestKit.contactSheet(sheet)))
            println(table)
        }
    }

    @Test
    fun `a synthetic recording goes through the corpus run end to end`() {
        val frames = keyframesOf(SyntheticRoomScan.frames)
        val file = folder.newFile("synthetic-room.svscan").apply { writeBytes(recording(frames, SyntheticRoomScan.cloud)) }
        val out = folder.newFolder("out")

        val recognition = TruthDetector(frames, TruthDetector.ROOM).use { run(file, it, out) }!!

        val labels = recognition.items.map { it.label }
        for (expected in listOf(SceneLabel.Sofa, SceneLabel.Table, SceneLabel.Refrigerator)) {
            assertTrue("$expected in $labels", expected in labels)
        }
        val dir = File(out, "synthetic-room")
        val report = Json.parseToJsonElement(File(dir, "recognized.json").readText()) as kotlinx.serialization.json.JsonObject
        assertEquals(recognition.items.size, report.getValue("items").jsonArray.size)
        assertEquals(SceneRecognizer.MAX_FRAMES, report.getValue("frames").jsonArray.size)
        val pngs = dir.listFiles { f -> f.name.startsWith("keyframe-") && f.extension == "png" }.orEmpty()
        assertEquals(SceneRecognizer.MAX_FRAMES, pngs.size)
        assertTrue(pngs.all { SceneTestKit.sceneImage(it.readBytes())?.width == RerunSyntheticRoom.DEPTH_WIDTH })
        val timings = File(dir, "timings.txt").readLines()
        assertEquals(SceneRecognizer.MAX_FRAMES + 2, timings.size)
        assertTrue(timings.last(), timings.last().startsWith("total"))
    }

    @Test
    fun `with no detector, a recorded room goes through the corpus run for its door and window`() {
        val corpus = folder.newFolder("corpus")
        val frames = keyframesOf(HoledRoomScan.frames)
        File(corpus, "holed-room.svscan").writeBytes(recording(frames, HoledRoomScan.cloud))
        File(corpus, "sparse.svscan").writeBytes(recording(frames.take(1), cloud = null))
        File(corpus, "cut-short.svscan").writeBytes(ByteArray(CUT_SHORT_BYTES) { it.toByte() })
        File(corpus, "notes.txt").writeText("not a scan")
        val out = folder.newFolder("out")

        recognizeAll(scansUnder(corpus), out, model = null)

        val dir = File(out, "holed-room")
        val report = Json.parseToJsonElement(File(dir, "recognized.json").readText()).jsonObject
        assertEquals(NoDetector.name, report.getValue("detector").jsonPrimitive.content)
        val labels = report.getValue("items").jsonArray.map { it.jsonObject.getValue("label").jsonPrimitive.content }
        assertEquals(listOf(SceneLabel.Door.wireName, SceneLabel.Window.wireName), labels.sorted())
        assertTrue("no photo is read without a detector", report.getValue("frames").jsonArray.isEmpty())
        assertTrue(dir.listFiles { f -> f.extension == "png" }.orEmpty().isEmpty())
        val timings = File(dir, "timings.txt").readLines()
        assertEquals(2, timings.size)
        assertTrue(timings.last(), timings.last().startsWith("total"))
        assertEquals(listOf("holed-room"), out.listFiles { f -> f.isDirectory }.orEmpty().map { it.name })

        // What the run did, where its outputs are: which detector, and each scan's outcome.
        val summary = File(out, SUMMARY)
        assertTrue("no $SUMMARY in $out", summary.isFile)
        val lines = summary.readLines()
        assertTrue(lines.first(), lines.first().startsWith("detector: ${NoDetector.name}"))
        val outcomes = lines.drop(1).associate { it.substringBefore(": ") to it.substringAfter(": ") }
        assertEquals(listOf("cut-short.svscan", "holed-room.svscan", "sparse.svscan"), outcomes.keys.toList())
        assertTrue(outcomes.toString(), "not a readable scan" in outcomes.getValue("cut-short.svscan"))
        assertTrue(outcomes.toString(), "no dense cloud" in outcomes.getValue("sparse.svscan"))
        val found = outcomes.getValue("holed-room.svscan")
        assertTrue(found, SceneLabel.Door.wireName in found && SceneLabel.Window.wireName in found)
    }

    @Test
    fun `a model named but missing stops the run, and none runs without a detector`() {
        assertEquals(emptyList<Pair<String, File>>(), models(NO_MODEL))
        val model = folder.newFile("yolox-tiny.onnx")
        assertEquals(listOf("yolox-tiny" to model), models(model.path))

        val missing = File(folder.root, "yolox-small.onnx").path
        val failure = runCatching { models(model.path + File.pathSeparator + missing) }.exceptionOrNull()
        assertTrue("a missing model was dropped without a word", failure is IllegalArgumentException)
        assertTrue(failure!!.message, missing in failure.message.orEmpty())
    }

    /** Every `.svscan` and `.rrd` under [corpus], subfolders included, in name order. */
    private fun scansUnder(corpus: File): List<File> =
        corpus.walk().filter { it.isFile && it.extension in SCANS }.sorted().toList()

    /**
     * Recognises each of [scans] into [out] with [model] (name and file), or with [NoDetector] —
     * doors and windows only — when there is none. Each line it prints also goes to [SUMMARY], as
     * it comes: a run stopped by one scan still says how the others went.
     */
    private fun recognizeAll(scans: List<File>, out: File, model: Pair<String, File>?) {
        val summary = File(out.apply { mkdirs() }, SUMMARY).apply { writeText("") }
        val note = { line: String -> println(line); summary.appendText(line + "\n") }
        if (model == null) {
            note("detector: ${NoDetector.name} — doors and windows only ($MODEL=$NO_MODEL, or no network to download the app's)")
            scans.forEach { run(it, NoDetector, out, note) }
            return
        }
        val (name, file) = model
        note("detector: $name ($file), ${scans.size} scans")
        OnnxSceneDetector(mapped(file), name).use { detector -> scans.forEach { run(it, detector, out, note) } }
    }

    /** Every [KEYFRAME_STEP]th of [frames]: the photos a recording keeps. */
    private fun keyframesOf(frames: List<DepthFrame>) = frames.filterIndexed { i, _ -> i % KEYFRAME_STEP == 0 }

    /**
     * A `.svscan` as the demo writes one: each of [frames] as a pose and a JPEG photo, the
     * synthetic lens, and [cloud] as its dense cloud — none for a sparse scan.
     */
    private fun recording(frames: List<DepthFrame>, cloud: DenseCloud?): ByteArray {
        val paths = frames.indices.map { "images/%04d.jpg".format(it) }
        val events = frames.flatMapIndexed { k, frame ->
            val nanos = (k + 1) * FRAME_NANOS
            listOf(ArDebugEvent.CameraPose(nanos, frame.pose), ArDebugEvent.Image(nanos + 1, paths[k]))
        }
        val photos = frames.mapIndexed { k, frame -> ScanPhoto(paths[k], SceneTestKit.jpeg(frame), frame.pose) }
        val lens = ReplayLens.of(
            RerunSyntheticRoom.DEPTH_WIDTH.toFloat(), RerunSyntheticRoom.DEPTH_HEIGHT.toFloat(),
            RerunSyntheticRoom.FOCAL, RerunSyntheticRoom.FOCAL,
        )!!
        val device = ScanDevice("android", "Pixel 9", ScanDevice.TIER_DEPTH, ScanDevice.SOURCE_RAW_DEPTH)
        return RerunScanFile.write(runBlocking { RerunCaptureBuilder.build(events, lens, photos, device, cloud) })
    }

    /**
     * Recognises the scan in [file] with [detector] — with [NoDetector], its openings only, no photo
     * read, as the app's first pass — and writes its outputs under [out]; `null`
     * (and a line to [note] saying why) for a file that is no readable scan or has no dense cloud.
     */
    private fun run(file: File, detector: SceneDetector, out: File, note: (String) -> Unit = ::println): SceneRecognition? {
        val opened = open(file) ?: return null.also { note("${file.name}: not a readable scan") }
        val dense = opened.manifest.dense
        val cloud = dense?.let { opened.bytesOf(it.path) }?.let { SvpcCodec.decode(it) }
            ?: return null.also { note("${file.name}: no dense cloud (a sparse scan): nothing to lift into") }
        val objects = ScanObjects.of(cloud, dense.voxelM)
        val keyframes = SceneRecognizer.keyframes(opened.trace, opened.manifest.lens)
        val images = HashMap<String, SceneImage>()
        val recognition = runBlocking {
            val frames = if (detector == NoDetector) 0 else SceneRecognizer.MAX_FRAMES
            SceneRecognizer(detector, maxFrames = frames).recognize(keyframes, cloud, objects, image = { path ->
                opened.bytesOf(path)?.let { SceneTestKit.sceneImage(it) }?.also { images[path] = it }
            })
        }
        val dir = File(out, file.nameWithoutExtension).apply { mkdirs() }
        File(dir, "recognized.json").writeText(SceneInventory.report(recognition, file.name).toString())
        val timings = StringBuilder("keyframe\tpath\tdecode_ms\tdetect_ms\tlift_ms\tdetections\tlifted\n")
        recognition.frames.forEachIndexed { k, frame ->
            val image = images[frame.keyframe.path] ?: return@forEachIndexed
            val annotated = SceneTestKit.annotate(image, frame.detections, recognition.items, frame.keyframe.camera)
            File(dir, "keyframe-%02d.png".format(k)).writeBytes(SceneTestKit.png(annotated))
            timings.append("${frame.keyframe.index}\t${frame.keyframe.path}\t${frame.decodeMillis}\t")
                .append("${frame.detectMillis}\t${frame.liftMillis}\t${frame.detections.size}\t${frame.lifted.size}\n")
        }
        timings.append("total\t\t\t\t\t\t${recognition.totalMillis} ms, ${recognition.frames.size} keyframes\n")
        File(dir, "timings.txt").writeText(timings.toString())
        note("${file.name}: " + SceneInventory.lines(recognition.items).joinToString("; ").ifEmpty { "nothing" } +
            " (${recognition.totalMillis} ms)")
        return recognition
    }

    private fun open(file: File): OpenedCapture? = runCatching {
        val bytes = file.readBytes()
        when (file.extension) {
            "rrd" -> RerunRrdReader.recording(bytes, SceneTestKit.ImageIoCodec).pack.open()
            else -> RerunScanFile.read(bytes)?.open()
        }
    }.getOrNull()

    /**
     * The models to run, by name: none for [NO_MODEL]; the files [spec] names (`:`-separated) —
     * one that is no file stops the run, a typo never drops it to no detector unseen; else the ONNX
     * export of the app's detector, downloaded once into the temp folder by the app's own store.
     * Empty when that download fails (no network).
     */
    private fun models(spec: String?): List<Pair<String, File>> {
        if (spec == NO_MODEL) return emptyList()
        spec?.let { paths ->
            return paths.split(File.pathSeparator).map(::File).map { file ->
                require(file.isFile) { "$MODEL names $file, which is not a file" }
                file.nameWithoutExtension to file
            }
        }
        val cache = File(System.getProperty("java.io.tmpdir"), DOWNLOAD_DIR)
        val file = runBlocking { SceneModelStore(cache, asset = ONNX_EXPORT).fetch() }
        return listOfNotNull(file?.let { SceneModel.NAME to it })
    }

    /** [model] mapped in memory, as the app maps its downloaded copy: a direct buffer, never on the heap. */
    private fun mapped(model: File) = FileChannel.open(model.toPath()).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }

    private companion object {
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"
        const val PHOTOS = "SCENEVIEW_SCENE_PHOTOS"
        const val MODEL = "SCENEVIEW_SCENE_MODEL"

        /**
         * YOLOX-Tiny 0.1.1rc0 as its repository exports it, which the app's `.tflite` was converted
         * from: still on `assets-v1` under its own hash, for the JVM, where only ONNX Runtime runs.
         */
        val ONNX_EXPORT = SceneModel.ASSET.copy(
            title = "YOLOX-Tiny (COCO object detector, ONNX)",
            file = "427cc366d34e27ff7a03e2899b5e3671425c262ea2291f88bb942bc1cc70b0f7",
            sha256 = "427cc366d34e27ff7a03e2899b5e3671425c262ea2291f88bb942bc1cc70b0f7",
            bytes = 20_219_662L,
        )

        /** As [MODEL]: no detector, no download — the doors and windows only, as a phone without one. */
        const val NO_MODEL = "none"

        /** Under [OUT]: the detector of the run, then one line per scan — what was found, or why it was skipped. */
        const val SUMMARY = "summary.txt"
        const val CUT_SHORT_BYTES = 64

        /** Under `java.io.tmpdir`: kept between runs, so the detector downloads once per machine. */
        const val DOWNLOAD_DIR = "sceneview-scene-detector"
        val SCANS = setOf("svscan", "rrd")
        val IMAGES = setOf("jpg", "jpeg", "png")
        const val KEYFRAME_STEP = 6
        const val FRAME_NANOS = 100_000_000L
        const val NANOS_PER_MS = 1_000_000L
        const val WARM_UP = 64
    }
}
