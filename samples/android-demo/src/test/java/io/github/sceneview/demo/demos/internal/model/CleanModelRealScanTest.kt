package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.RerunGlbMesh
import io.github.sceneview.demo.demos.internal.RerunMeshSimplifier
import io.github.sceneview.demo.demos.internal.RerunScanModel
import io.github.sceneview.demo.demos.internal.RerunTsdf
import io.github.sceneview.demo.demos.internal.ScanGround
import io.github.sceneview.demo.demos.internal.ScanGroundMeshes
import io.github.sceneview.demo.demos.internal.groundSource
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * The clean model on a room as a phone really scans it ([RealScanRoom]): walls swelling by ±2 cm,
 * two walls scanned twice 3 cm apart, small key photos taken close and at an angle, then the same
 * room with a pleated curtain and a sofa, and with a TV standing off a wall and a door set into
 * another. The finished model must come out light — about the simplifier's budget, textured, and
 * a file within [CleanModel]'s fixed cap — with its walls flat and single, its furniture, TV and
 * door still in relief, and within a phone's heap; out of it, the raw surface.
 *
 * Each case gathers every figure and fails once, with all of them: CI prints only a failure.
 */
class CleanModelRealScanTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a bare room scanned with real noise comes out light, flat and single-walled`() {
        check(furnished = false)
    }

    @Test
    fun `a furnished room keeps its curtain and sofa in relief while its walls flatten`() {
        check(furnished = true)
    }

    @Test
    fun `a TV off its wall and a door set into another keep their relief while the walls flatten`() {
        check(furnished = false, reliefs = true)
    }

    @Test
    fun `a small room keeps its photo texture, however light its raw surface`() {
        // A small room's raw surface weighs less than its textured model: the atlas alone is a few
        // hundred kilobytes. The texture is what the finish is for, so only a fixed cap bounds it.
        // The scan's lace of holes would hold the simplifier five times over that budget: its
        // borders collapse too.
        val raw = RerunMeshSimplifier.simplify(RealScanRoom.raw(furnished = true), SMALL_ROOM_TRIANGLES, collapseBorders = true)
        val rawGlb = RerunMeshGlb.write(raw)
        val result = CleanModel.build(raw, RealScanRoom.modelPhotos(furnished = true), JvmAtlasEncoder)
        val stats = result.stats
        val report = "small room: raw ${raw.triangleCount} triangles, glb ${rawGlb.size / KB} KB -> " +
            "${stats.finalTriangles} triangles, glb ${result.glb.size / KB} KB, atlas ${stats.atlasWidth}x${stats.atlasHeight}" +
            ", halved ${stats.atlasHalvings}, guarded ${stats.glbGuarded}" +
            ", textured ${stats.texture?.let { percent(it.texturedShare) } ?: "none"}" +
            ", dropped at ${stats.droppedTextureShare?.let { percent(it) } ?: "-"}"
        println(report)
        assertTrue("the texture was dropped | $report", stats.texture != null)
        assertEquals("the atlas was halved | $report", 0, stats.atlasHalvings)
        assertFalse("the size guard ran | $report", stats.glbGuarded)
        assertTrue("expected the textured model heavier than the raw surface | $report", result.glb.size > rawGlb.size)
        assertTrue("glb ${result.glb.size} over the cap | $report", result.glb.size <= MAX_GLB_BYTES)
        assertTrue("glb: ${GlbCheck.problems(result.glb)} | $report", GlbCheck.problems(result.glb).isEmpty())
    }

    @Test
    fun `a room photographed along two of its walls keeps its texture where the photos saw it`() {
        // The long walls' photos only: under half the room seen, as the largest real room was.
        // What they saw is textured; the rest keeps its vertex colours.
        val all = RealScanRoom.modelPhotos(furnished = false)
        val photos = ModelPhotos(all.lens, all.photos.filterIndexed { i, _ -> i in LONG_WALL_PHOTOS })
        val result = CleanModel.build(RealScanRoom.raw(furnished = false), photos, JvmAtlasEncoder)
        val stats = result.stats
        val model = checkNotNull(result.model) { "out of memory in ${stats.outOfMemoryIn}" }
        val share = stats.texture?.texturedShare ?: stats.droppedTextureShare
        val report = "long walls photographed: ${photos.photos.size}/${all.photos.size} photos" +
            ", textured ${stats.texture?.let { percent(it.texturedShare) } ?: "none"}" +
            ", dropped at ${stats.droppedTextureShare?.let { percent(it) } ?: "-"}" +
            ", ${model.texturedIndices.size / 3} textured and ${model.plainIndices.size / 3} plain triangles" +
            ", glb ${result.glb.size / KB} KB"
        println(report)
        assertTrue("the photos saw most of the room: no partial case | $report", share != null && share < PARTLY_SEEN)
        assertTrue("the texture was dropped | $report", stats.texture != null)
        assertTrue("nothing textured | $report", model.texturedIndices.isNotEmpty())
        assertTrue("nothing left to its vertex colours | $report", model.plainIndices.isNotEmpty())
        assertTrue("glb ${result.glb.size} over the cap | $report", result.glb.size <= MAX_GLB_BYTES)
        assertTrue("glb: ${GlbCheck.problems(result.glb)} | $report", GlbCheck.problems(result.glb).isEmpty())
    }

    @Test
    fun `a model over the cap comes within it, however heavy its raw surface`() {
        // An atlas that compresses as badly as a noisy photo's: a room's raw surface weighs more
        // than the textured model, and the file must still stay within the cap.
        val raw = RealScanRoom.raw(furnished = false)
        val rawGlb = RerunMeshGlb.write(raw)
        val result = CleanModel.build(raw, RealScanRoom.modelPhotos(furnished = false), PaddedAtlasEncoder(HEAVY_ATLAS_BYTES))
        val stats = result.stats
        val report = "heavy atlas: raw ${raw.triangleCount} triangles, glb ${rawGlb.size / KB} KB -> " +
            "${stats.finalTriangles} triangles, glb ${result.glb.size / KB} KB, halved ${stats.atlasHalvings}" +
            ", guarded ${stats.glbGuarded}, textured ${stats.texture?.let { percent(it.texturedShare) } ?: "none"}"
        println(report)
        assertTrue("glb ${result.glb.size} over the cap | $report", result.glb.size <= MAX_GLB_BYTES)
        assertTrue("glb: ${GlbCheck.problems(result.glb)} | $report", GlbCheck.problems(result.glb).isEmpty())
    }

    @Test
    fun `a small scan whose atlas alone outweighs the cap comes within it`() {
        // A corner's few faces, under the size guard's floor, and an atlas heavier than the cap.
        val raw = crop(RealScanRoom.raw(furnished = false), SMALL_CORNER)
        val result = CleanModel.build(raw, RealScanRoom.modelPhotos(furnished = false), PaddedAtlasEncoder(MAX_GLB_BYTES))
        val stats = result.stats
        val report = "small corner: raw ${raw.triangleCount} triangles -> " +
            "${stats.simplifiedTriangles} simplified, ${stats.finalTriangles} triangles, glb ${result.glb.size / KB} KB" +
            ", textured ${stats.texture?.let { percent(it.texturedShare) } ?: "none"}" +
            ", dropped at ${stats.droppedTextureShare?.let { percent(it) } ?: "-"}, guarded ${stats.glbGuarded}"
        println(report)
        assertTrue("glb ${result.glb.size} over the cap | $report", result.glb.size <= MAX_GLB_BYTES)
        assertTrue("glb: ${GlbCheck.problems(result.glb)} | $report", GlbCheck.problems(result.glb).isEmpty())
    }

    @Test
    fun `a finish that runs out of memory gives back the raw surface`() {
        val photos = RealScanRoom.modelPhotos(furnished = false)
        val raw = crop(RealScanRoom.raw(furnished = false), CORNER)
        val rawGlb = RerunMeshGlb.write(raw)
        // The heap runs out once the shape is simplified, as the photos are decoded.
        val result = CleanModel.build(raw, photos, JvmAtlasEncoder, progress = outOfMemoryFrom(TEXTURING))
        assertTrue("the raw surface was not kept", result.stats.rawKept)
        assertTrue("expected the raw surface's own .glb", result.glb.contentEquals(rawGlb))
        assertEquals(raw.triangleCount, result.stats.finalTriangles)
        assertNull("a finished model with the raw surface's .glb", result.model)

        // Worked in place, out of memory while cleaning: the .glb written before comes back.
        val consumed = CleanModel.build(
            crop(RealScanRoom.raw(furnished = false), CORNER),
            photos,
            JvmAtlasEncoder,
            consume = true,
            rawGlb = rawGlb,
            progress = outOfMemoryFrom(CLEANING),
        )
        assertTrue("the raw surface was not kept", consumed.stats.rawKept)
        assertTrue("expected the .glb written before", consumed.glb === rawGlb)
        assertNull("a finished model with the raw surface's .glb", consumed.model)
    }

    @Test
    fun `the size guard reports its progress, so a finish left mid-guard stops there`() {
        val raw = crop(RealScanRoom.raw(furnished = false), CORNER)
        val padded = PaddedAtlasEncoder(MAX_GLB_BYTES)
        var atlases = 0
        val encoder = AtlasEncoder { argb, width, height -> atlases++; padded.jpeg(argb, width, height) }
        // Each progress report, with the atlases written by then: a caller stops the finish there.
        val reports = ArrayList<Pair<Float, Int>>()
        val result = CleanModel.build(raw, RealScanRoom.modelPhotos(furnished = false), encoder) { reports += it to atlases }
        val afterLastAtlas = reports.filter { it.second == atlases }.map { it.first }
        val report = "corner: $atlases atlases, guarded ${result.stats.glbGuarded}, reports after the last: $afterLastAtlas"
        println(report)
        assertTrue("the atlas was never written | $report", atlases > 0)
        assertTrue("the size guard never ran | $report", result.stats.glbGuarded)
        assertTrue("expected a report before the end, within the guard | $report", afterLastAtlas.first() < 1f)
    }

    @Test
    fun `the furnished room finishes within a 192 MB heap`() = finishesWithinHeap(HeapCappedFinish.ROOM)

    @Test
    fun `a scan meshed to the most a 192 MB heap takes finishes within it`() = finishesWithinHeap(HEAP_RAW_TRIANGLES)

    @Test
    fun `a scan meshed to the most a 192 MB heap takes finishes again within it`() =
        finishesWithinHeap(HEAP_RAW_TRIANGLES, again = true)

    @Test
    fun `the extraction cap stays within what a heap was seen to finish`() {
        val phone = CleanModel.maxRawTriangles(HeapCappedFinish.HEAP_MB * MB)
        assertTrue("192 MB meshes $phone, over the $HEAP_RAW_TRIANGLES finished in it", phone <= HEAP_RAW_TRIANGLES)
        assertTrue("192 MB meshes $phone, under the longest recordings", phone >= LONGEST_RECORDING_TRIANGLES)
        assertEquals(RerunMarchingCubes.MAX_TRIANGLES, CleanModel.maxRawTriangles(320 * MB))
        val tiny = CleanModel.maxRawTriangles(16 * MB)
        assertTrue("a tiny heap still meshes a room: $tiny", tiny in 1 until phone)
    }

    /**
     * [HeapCappedFinish] of a raw surface of [triangles] in a JVM capped at a phone's heap. What
     * stays live, not what a sampler saw used: the heap after each full collection, garbage gone,
     * plus, while it finishes, the copy the screen hands the renderer to show the raw surface
     * meanwhile — ART allocates a direct buffer in its Java heap, which the child does not hold
     * then. At the swap the child holds it all: both models, the finished one's copy and its ground.
     * [again]: the raw surface a finish ran out of memory on, read back with its scan and shown
     * while it finishes again — no second `.glb` of it beside the one shown.
     */
    private fun finishesWithinHeap(triangles: Int, again: Boolean = false) {
        val log = folder.newFile("finish.log")
        val java = File(File(System.getProperty("java.home"), "bin"), "java").path
        val classpath = (
            System.getProperty("java.class.path").split(File.pathSeparator) +
                listOf(HeapCappedFinish::class.java, CleanModel::class.java, Unit::class.java, Json::class.java)
                    .mapNotNull { codeSource(it) }
            ).filter { it.isNotEmpty() }.distinct().joinToString(File.pathSeparator)
        val gcLog = File(folder.root, "gc.log")
        val process = ProcessBuilder(
            java, "-Xmx${HeapCappedFinish.HEAP_MB}m", "-XX:+UseSerialGC", "-Djava.awt.headless=true",
            "-Xlog:gc:file=${gcLog.path}:timemillis", "-cp", classpath, HeapCappedFinish::class.java.name, "$triangles",
            if (again) HeapCappedFinish.AGAIN else HeapCappedFinish.FIRST,
        ).redirectErrorStream(true).redirectOutput(log).start()
        val done = process.waitFor(CHILD_TIMEOUT_S, TimeUnit.SECONDS)
        if (!done) process.destroyForcibly().waitFor()
        val output = log.readText()
        val stages = Regex("${HeapCappedFinish.STAGE}(\\w+) (\\d+)").findAll(output)
            .map { it.groupValues[2].toLong() to it.groupValues[1] }.toList()
        // The finish's own collections: the TSDF and the room tiled into the scan are gone by then.
        val collections = fullCollections(gcLog.readText()).filter { c -> stages.any { it.first <= c.atMs } }
        val tail = "${HeapCappedFinish.HEAP_MB} MB heap, live peak by stage ${stagePeaks(collections, stages)}: " +
            output.takeLast(LOG_TAIL)
        println(tail)
        assertTrue("timed out after $CHILD_TIMEOUT_S s: $tail", done)
        assertEquals(tail, 0, process.exitValue())
        assertTrue(tail, output.contains(HeapCappedFinish.FINISHED))
        val swapAt = stages.firstOrNull { it.second == HeapCappedFinish.SWAP }?.first
        assertTrue("no swap reported: $tail", swapAt != null)
        val finishing = collections.filter { it.atMs < swapAt!! }
        val swapping = collections.filter { it.atMs >= swapAt!! }
        assertTrue("no full collection logged: $tail", finishing.isNotEmpty())
        assertTrue("no full collection at the swap: $tail", swapping.isNotEmpty())

        val live = finishing.maxOf { it.live }
        val shown = Regex("${HeapCappedFinish.RAW_GLB}(\\d+)").find(output)?.groupValues?.get(1)?.toLong()
        assertTrue("no raw GLB size reported: $tail", shown != null)
        val swap = swapping.maxOf { it.live }
        val peak = maxOf(live + shown!!, swap)
        val bar = HeapCappedFinish.HEAP_MB.toLong() * MB
        println(
            "live peak ${live / MB} MB + shown raw GLB ${shown / MB} MB, swap ${swap / MB} MB: " +
                "${peak / MB} MB of ${bar / MB} MB",
        )
        assertTrue("live peak ${peak / MB} MB over ${bar / MB} MB: $tail", peak <= bar)
        val second = Regex("${HeapCappedFinish.SECOND_GLB}(\\d+)").find(output)?.groupValues?.get(1)?.toLong()
        assertEquals("a second raw .glb beside the one shown, of ${shown / MB} MB: $tail", 0L, second)
    }

    /** A full collection: when it ended, and what the heap held after it. */
    private class Collection(val atMs: Long, val live: Long)

    /**
     * The full collections of a `-Xlog:gc:file=…:timemillis` log
     * (`[1729512345678ms] GC(12) Pause Full (Allocation Failure) 172M->135M(185M)`). A young
     * collection is no live figure: the old objects it skips may be garbage, and one that cannot
     * promote collects nothing (`172M->172M`) before the full one that follows.
     */
    private fun fullCollections(gcLog: String): List<Collection> =
        Regex("""\[(\d+)ms\][^\n]*Pause Full [^\n]*?\d+[KMG]->(\d+)([KMG])\(""").findAll(gcLog).map {
            val unit = when (it.groupValues[3]) {
                "K" -> KB.toLong()
                "M" -> MB
                else -> MB * KB
            }
            Collection(it.groupValues[1].toLong(), it.groupValues[2].toLong() * unit)
        }.toList()

    /** The most each stage of the finish left live, in MB, by when each started ([HeapCappedFinish.STAGE]). */
    private fun stagePeaks(collections: List<Collection>, stages: List<Pair<Long, String>>): Map<String, Long> =
        collections.groupBy { c -> stages.last { it.first <= c.atMs }.second }
            .mapValues { (_, inStage) -> inStage.maxOf { it.live } / MB }

    /** Throws [OutOfMemoryError] once the finish reports [share] done, as a phone's heap would run out there. */
    private fun outOfMemoryFrom(share: Float): (Float) -> Unit = { done ->
        if (done >= share) throw OutOfMemoryError("finish at $done")
    }

    private fun check(furnished: Boolean, reliefs: Boolean = false) {
        val raw = RealScanRoom.raw(furnished, reliefs = reliefs)
        val rawGlb = RerunMeshGlb.write(raw)
        val result = CleanModel.build(raw, RealScanRoom.modelPhotos(furnished, reliefs), JvmAtlasEncoder)
        val stats = result.stats
        val model = checkNotNull(result.model) { "out of memory in ${stats.outOfMemoryIn}" }
        val finalSurface = Surface(model.positions, model.texturedIndices + model.plainIndices)
        val rawSurface = Surface(raw.positions, raw.indices)
        val excluded = { x: Float, y: Float, z: Float ->
            (furnished && nearFurniture(x, y, z)) || (reliefs && nearRelief(x, y, z))
        }
        val rawWalls = walls(rawSurface, excluded)
        val finalWalls = walls(finalSurface, excluded)
        val rawLayers = layers(rawSurface)
        val finalLayers = layers(finalSurface)
        val texture = stats.texture
        // The floor the eye reads: as much of it as the scan saw, and no hole the finish opened.
        val rawFloor = FloorArea.of(raw.positions, raw.indices, RealScanRoom.ROOM.y0)
        val finalFloor = FloorArea.of(model.positions, model.texturedIndices + model.plainIndices, RealScanRoom.ROOM.y0)
        val floorRatio = finalFloor.area / rawFloor.area
        val room = RealScanRoom.ROOM
        val ofRoom = finalFloor.area / ((room.x1 - room.x0) * (room.z1 - room.z0))

        val report = StringBuilder()
        report.append(if (furnished) "furnished room" else "bare room").append(if (reliefs) " with a TV and a door" else "")
            .append(": raw ${raw.triangleCount} triangles, glb ${rawGlb.size / KB} KB")
            .append(" -> ${stats.finalTriangles} triangles (${stats.simplifiedTriangles} simplified, target ${stats.simplifyTarget})")
            .append(", glb ${stats.glbBytes / KB} KB, atlas ${stats.atlasWidth}x${stats.atlasHeight}")
            .append("; flat ${percent(stats.geometry.flatShare)}, ${stats.geometry.planes} planes")
            .append(", doubled ${stats.geometry.doubledTriangles} triangles")
            .append("; textured ${texture?.let { percent(it.texturedShare) } ?: "none"}")
            .append(texture?.let { ", ${it.photosUsed}/${it.photosIn} photos" } ?: "")
            .append(stats.droppedTextureShare?.let { " (texture dropped at ${percent(it)})" } ?: "")
            .append("; time prepare ${stats.prepareMs}, clean ${stats.cleanMs}, simplify ${stats.simplifyMs}")
            .append(", texture ${stats.textureMs}, write ${stats.writeMs} = ${stats.totalMs} ms")
            .append("; walls rms ${mm(rawWalls.rms)} -> ${mm(finalWalls.rms)} mm (worst ${mm(finalWalls.worst)})")
            .append("; layers ${ratio(rawLayers)} -> ${ratio(finalLayers)}")
            .append("; floor ${"%.3f".format(floorRatio)} of the raw one's area (${"%.3f".format(ofRoom)} of the room's)")
            .append(", holes ${rawFloor.innerLoops} -> ${finalFloor.innerLoops}")

        val problems = mutableListOf<String>()
        if (floorRatio !in MIN_FLOOR_KEPT..MAX_FLOOR_GROWN) problems += "floor ${"%.3f".format(floorRatio)} of the raw one's"
        if (finalFloor.innerLoops > rawFloor.innerLoops) problems += "floor holes ${rawFloor.innerLoops} -> ${finalFloor.innerLoops}"
        if (ofRoom > MAX_FLOOR_OF_ROOM) problems += "floor counted twice: ${"%.3f".format(ofRoom)} of the room's"
        if (raw.triangleCount !in MIN_RAW_TRIANGLES..MAX_RAW_TRIANGLES) problems += "raw ${raw.triangleCount} triangles"
        if (stats.finalTriangles > MAX_FINAL_TRIANGLES) problems += "${stats.finalTriangles} triangles"
        // The file shared and kept: within the cap, seams and atlas counted, and textured.
        if (result.glb.size > MAX_GLB_BYTES) problems += "glb ${result.glb.size} over the cap"
        if (texture == null) problems += "texture dropped"
        val minFlat = if (furnished) MIN_FLAT_FURNISHED else MIN_FLAT_BARE
        if (stats.geometry.flatShare < minFlat) problems += "flat ${percent(stats.geometry.flatShare)}"
        if (finalWalls.rms > MAX_WALL_RMS_M || finalWalls.worst > MAX_WALL_WORST_M) problems += "walls not flat"
        if (finalLayers.any { it > MAX_LAYERS }) problems += "walls still doubled"
        if (furnished) {
            val rawCurtain = curtainRelief(rawSurface)
            val finalCurtain = curtainRelief(finalSurface)
            val rawSeat = seatRelief(rawSurface)
            val finalSeat = seatRelief(finalSurface)
            report.append("; curtain relief ${mm(rawCurtain)} -> ${mm(finalCurtain)} mm")
                .append(", seat relief ${mm(rawSeat)} -> ${mm(finalSeat)} mm")
            if (finalCurtain < rawCurtain * MIN_CURTAIN_KEPT) problems += "curtain flattened"
            if (finalSeat < rawSeat * MIN_SEAT_KEPT) problems += "sofa flattened"
        }
        if (reliefs) {
            val rawTv = tvRelief(rawSurface)
            val finalTv = tvRelief(finalSurface)
            val rawDoor = doorRelief(rawSurface)
            val finalDoor = doorRelief(finalSurface)
            report.append("; TV relief ${mm(rawTv)} -> ${mm(finalTv)} mm")
                .append(", door relief ${mm(rawDoor)} -> ${mm(finalDoor)} mm")
            if (finalTv < rawTv * MIN_RELIEF_KEPT) problems += "TV pressed onto its wall"
            if (finalDoor < rawDoor * MIN_RELIEF_KEPT) problems += "door pressed onto its wall"
        }
        GlbCheck.problems(result.glb).takeIf { it.isNotEmpty() }?.let { problems += "glb: $it" }
        if (stats.totalMs > MAX_MS) problems += "${stats.totalMs} ms"
        println(report)
        assertTrue("$problems | $report", problems.isEmpty())
    }

    /** A triangle soup's faces: unit normal, area, centroid. */
    private class Surface(val positions: FloatArray, val indices: IntArray) {
        val count = indices.size / 3
        val normal = FloatArray(count * 3)
        val centroid = FloatArray(count * 3)
        val area = FloatArray(count)

        init {
            for (t in 0 until count) {
                val a = indices[t * 3] * 3
                val b = indices[t * 3 + 1] * 3
                val c = indices[t * 3 + 2] * 3
                val ux = positions[b] - positions[a]
                val uy = positions[b + 1] - positions[a + 1]
                val uz = positions[b + 2] - positions[a + 2]
                val vx = positions[c] - positions[a]
                val vy = positions[c + 1] - positions[a + 1]
                val vz = positions[c + 2] - positions[a + 2]
                val nx = uy * vz - uz * vy
                val ny = uz * vx - ux * vz
                val nz = ux * vy - uy * vx
                val length = sqrt(nx * nx + ny * ny + nz * nz)
                area[t] = length / 2f
                if (length > 0f) {
                    normal[t * 3] = nx / length
                    normal[t * 3 + 1] = ny / length
                    normal[t * 3 + 2] = nz / length
                }
                for (axis in 0 until 3) {
                    centroid[t * 3 + axis] = (positions[a + axis] + positions[b + axis] + positions[c + axis]) / 3f
                }
            }
        }
    }

    private class Flatness(val rms: Float, val worst: Float)

    /**
     * The room's six surfaces — floor, ceiling, four walls — each measured as the RMS distance of
     * its faces' vertices to their own mean plane, area-weighted: faces within [NEAR_M] of the
     * surface, facing the room, [EDGE_M] clear of the other surfaces, and away from [excluded].
     */
    private fun walls(surface: Surface, excluded: (Float, Float, Float) -> Boolean): Flatness {
        val room = RealScanRoom.ROOM
        val lo = floatArrayOf(room.x0, room.y0, room.z0)
        val hi = floatArrayOf(room.x1, room.y1, room.z1)
        var totalWeight = 0.0
        var totalVariance = 0.0
        var worst = 0f
        for (axis in 0 until 3) {
            for (side in 0 until 2) {
                val at = if (side == 0) lo[axis] else hi[axis]
                val inward = if (side == 0) 1f else -1f
                var w = 0.0
                var sum = 0.0
                var squares = 0.0
                for (t in 0 until surface.count) {
                    val c = t * 3
                    if (abs(surface.centroid[c + axis] - at) > NEAR_M) continue
                    if (surface.normal[c + axis] * inward < FACING) continue
                    val clear = (0 until 3).all { a ->
                        a == axis || surface.centroid[c + a] in lo[a] + EDGE_M..hi[a] - EDGE_M
                    }
                    if (!clear) continue
                    if (excluded(surface.centroid[c], surface.centroid[c + 1], surface.centroid[c + 2])) continue
                    val weight = surface.area[t] / 3.0
                    for (k in 0 until 3) {
                        val d = surface.positions[surface.indices[c + k] * 3 + axis].toDouble()
                        w += weight
                        sum += weight * d
                        squares += weight * d * d
                    }
                }
                if (w <= 0.0) continue
                val mean = sum / w
                val variance = max(0.0, squares / w - mean * mean)
                worst = max(worst, sqrt(variance).toFloat())
                totalWeight += w
                totalVariance += variance * w
            }
        }
        val rms = if (totalWeight > 0.0) sqrt(totalVariance / totalWeight).toFloat() else 0f
        return Flatness(rms, worst)
    }

    /** The curtain's and the sofa's corners of the room, left out of the walls' flatness. */
    private fun nearFurniture(x: Float, @Suppress("UNUSED_PARAMETER") y: Float, z: Float): Boolean =
        (x > CURTAIN_ZONE_X && z in CURTAIN_ZONE_Z0..CURTAIN_ZONE_Z1) ||
            (z < SOFA_ZONE_Z && x in -SOFA_ZONE_X..SOFA_ZONE_X)

    /** The TV's and the door's walls around them, left out of the walls' flatness. */
    private fun nearRelief(x: Float, y: Float, z: Float): Boolean =
        RealScanRoom.TV.sdf(x, y, z) < RELIEF_MARGIN_M || RealScanRoom.DOOR.sdf(x, y, z) < RELIEF_MARGIN_M

    /** How far the TV's screen stands off wall x = +2.5. */
    private fun tvRelief(surface: Surface): Float =
        relief(surface, RealScanRoom.TV, axis = 0, wallAt = RealScanRoom.ROOM.x1, inward = -1f)

    /** How far the door's leaf sits into wall z = -3.5. */
    private fun doorRelief(surface: Surface): Float =
        relief(surface, RealScanRoom.DOOR, axis = 2, wallAt = RealScanRoom.ROOM.z0, inward = 1f)

    /**
     * How far a flat [panel] on the wall across [axis] at [wallAt] stands off or sits into it: the
     * area-weighted depth of the faces turned to the room within [NEAR_M] of the wall, on the
     * panel [RELIEF_INSET_M] clear of its edges, against that of the wall in a [RELIEF_RING_M]
     * ring around it, [EDGE_M] clear of the other surfaces.
     */
    private fun relief(surface: Surface, panel: Box, axis: Int, wallAt: Float, inward: Float): Float {
        val room = RealScanRoom.ROOM
        val along = if (axis == 0) 2 else 0
        var panelArea = 0.0
        var panelDepth = 0.0
        var ringArea = 0.0
        var ringDepth = 0.0
        for (t in 0 until surface.count) {
            val c = t * 3
            if (surface.normal[c + axis] * inward < FACING) continue
            val depth = surface.centroid[c + axis]
            if (abs(depth - wallAt) > NEAR_M) continue
            val u = surface.centroid[c + along]
            val y = surface.centroid[c + 1]
            val onPanel = u in panel.lo(along) + RELIEF_INSET_M..panel.hi(along) - RELIEF_INSET_M &&
                y in panel.y0 + RELIEF_INSET_M..panel.y1 - RELIEF_INSET_M
            val nearPanel = u in panel.lo(along) - RELIEF_INSET_M..panel.hi(along) + RELIEF_INSET_M &&
                y in panel.y0 - RELIEF_INSET_M..panel.y1 + RELIEF_INSET_M
            val inRing = u in panel.lo(along) - RELIEF_RING_M..panel.hi(along) + RELIEF_RING_M &&
                y in panel.y0 - RELIEF_RING_M..panel.y1 + RELIEF_RING_M &&
                u in room.lo(along) + EDGE_M..room.hi(along) - EDGE_M && y in room.y0 + EDGE_M..room.y1 - EDGE_M
            if (onPanel) {
                panelArea += surface.area[t]
                panelDepth += surface.area[t] * depth
            } else if (inRing && !nearPanel) {
                ringArea += surface.area[t]
                ringDepth += surface.area[t] * depth
            }
        }
        if (panelArea <= 0.0 || ringArea <= 0.0) return 0f
        return abs(panelDepth / panelArea - ringDepth / ringArea).toFloat()
    }

    /**
     * How many sheets the doubled walls show: the area of the faces within [NEAR_M] of each wall
     * and turned along its normal, inside the doubled patch, over the patch's area — about 2 for
     * a wall scanned twice, 1 for a single wall.
     */
    private fun layers(surface: Surface): List<Float> {
        var xWall = 0.0
        var zWall = 0.0
        val room = RealScanRoom.ROOM
        for (t in 0 until surface.count) {
            val c = t * 3
            val x = surface.centroid[c]
            val y = surface.centroid[c + 1]
            val z = surface.centroid[c + 2]
            if (y !in LAYER_Y0..LAYER_Y1) continue
            if (abs(x - room.x0) <= NEAR_M && abs(surface.normal[c]) >= ALONG && z in X_LAYER_Z0..X_LAYER_Z1) {
                xWall += surface.area[t]
            }
            if (abs(z - room.z1) <= NEAR_M && abs(surface.normal[c + 2]) >= ALONG && x in -Z_LAYER_X..Z_LAYER_X) {
                zWall += surface.area[t]
            }
        }
        val height = LAYER_Y1 - LAYER_Y0
        return listOf(
            (xWall / (height * (X_LAYER_Z1 - X_LAYER_Z0))).toFloat(),
            (zWall / (height * 2 * Z_LAYER_X)).toFloat(),
        )
    }

    /** The curtain's pleats: the area-weighted spread of its front's depth. */
    private fun curtainRelief(surface: Surface): Float = spread(surface, axis = 0) { t, x, y, z ->
        surface.normal[t * 3] < -AWAY && x in CURTAIN_X0..CURTAIN_X1 && y in CURTAIN_Y0..CURTAIN_Y1 &&
            z in CURTAIN_Z0..CURTAIN_Z1
    }

    /** The seat's cushions: the area-weighted spread of its top's height, the grooves between them included. */
    private fun seatRelief(surface: Surface): Float = spread(surface, axis = 1) { t, x, y, z ->
        surface.normal[t * 3 + 1] > AWAY && x in -SEAT_X..SEAT_X && y in SEAT_Y0..SEAT_Y1 && z in SEAT_Z0..SEAT_Z1
    }

    private fun spread(surface: Surface, axis: Int, keep: (Int, Float, Float, Float) -> Boolean): Float {
        var w = 0.0
        var sum = 0.0
        var squares = 0.0
        for (t in 0 until surface.count) {
            val c = t * 3
            if (!keep(t, surface.centroid[c], surface.centroid[c + 1], surface.centroid[c + 2])) continue
            val d = surface.centroid[c + axis].toDouble()
            w += surface.area[t]
            sum += surface.area[t] * d
            squares += surface.area[t] * d * d
        }
        if (w <= 0.0) return 0f
        val mean = sum / w
        return sqrt(max(0.0, squares / w - mean * mean)).toFloat()
    }

    /** [mesh]'s faces whose centroid lies in [box], their vertices renumbered. */
    private fun crop(mesh: RerunMesh, box: Box): RerunMesh {
        val remap = IntArray(mesh.vertexCount) { -1 }
        val kept = mutableListOf<Int>()
        var vertices = 0
        for (t in 0 until mesh.indices.size / 3) {
            val corners = (0 until 3).map { mesh.indices[t * 3 + it] }
            val inside = (0 until 3).all { axis ->
                val c = corners.sumOf { mesh.positions[it * 3 + axis].toDouble() } / 3.0
                c >= box.lo(axis) && c <= box.hi(axis)
            }
            if (!inside) continue
            for (v in corners) {
                if (remap[v] < 0) remap[v] = vertices++
                kept += remap[v]
            }
        }
        val positions = FloatArray(vertices * 3)
        val normals = FloatArray(vertices * 3)
        val colors = IntArray(vertices)
        for (v in 0 until mesh.vertexCount) {
            val to = remap[v]
            if (to < 0) continue
            mesh.positions.copyInto(positions, to * 3, v * 3, v * 3 + 3)
            mesh.normals.copyInto(normals, to * 3, v * 3, v * 3 + 3)
            colors[to] = mesh.colors[v]
        }
        return RerunMesh(positions, normals, colors, kept.toIntArray())
    }

    private fun Box.lo(axis: Int) = when (axis) { 0 -> x0; 1 -> y0; else -> z0 }
    private fun Box.hi(axis: Int) = when (axis) { 0 -> x1; 1 -> y1; else -> z1 }

    private fun codeSource(type: Class<*>): String? =
        runCatching { File(type.protectionDomain.codeSource.location.toURI()).path }.getOrNull()

    private fun percent(share: Float) = "%.1f%%".format(share * PERCENT)
    private fun mm(m: Float) = "%.1f".format(m * MM_PER_M)
    private fun ratio(values: List<Float>) = values.joinToString("/") { "%.2f".format(it) }

    private companion object {
        const val KB = 1024
        const val PERCENT = 100f
        const val MM_PER_M = 1000f

        const val MIN_RAW_TRIANGLES = 300_000
        const val MAX_RAW_TRIANGLES = 700_000

        /** The simplifier's budget, and a margin for the seams' split. */
        const val MAX_FINAL_TRIANGLES = 55_000
        const val MIN_FLAT_BARE = 0.8f
        const val MIN_FLAT_FURNISHED = 0.75f
        const val MAX_WALL_RMS_M = 0.003f
        const val MAX_WALL_WORST_M = 0.005f
        const val MAX_LAYERS = 1.3f

        /**
         * The finished floor's area over the raw one's: none of what the scan saw lost, and no more
         * gained than the small holes of its lace filled.
         */
        const val MIN_FLOOR_KEPT = 0.97f
        const val MAX_FLOOR_GROWN = 1.25f

        /** The finished floor over the room's: its holes filled, never a floor laid twice. */
        const val MAX_FLOOR_OF_ROOM = 1.01f
        const val MIN_CURTAIN_KEPT = 0.7f
        const val MIN_SEAT_KEPT = 0.6f

        /**
         * The share of the TV's and the door's 3.5 cm the model must keep: merged into their
         * walls, they keep under a tenth of it.
         */
        const val MIN_RELIEF_KEPT = 0.75f
        const val RELIEF_INSET_M = 0.1f
        const val RELIEF_RING_M = 0.4f
        const val RELIEF_MARGIN_M = 0.15f

        /** A desktop JVM's figure, a guard against a regression, not a phone's time. */
        const val MAX_MS = 15_000L

        const val NEAR_M = 0.06f
        const val EDGE_M = 0.2f
        const val FACING = 0.8f
        const val ALONG = 0.5f
        const val AWAY = 0.3f

        const val CURTAIN_ZONE_X = 2.0f
        const val CURTAIN_ZONE_Z0 = -1.4f
        const val CURTAIN_ZONE_Z1 = 1.2f
        const val SOFA_ZONE_Z = -2.3f
        const val SOFA_ZONE_X = 1.3f

        const val LAYER_Y0 = 0.55f
        const val LAYER_Y1 = 2.15f
        const val X_LAYER_Z0 = -0.85f
        const val X_LAYER_Z1 = 1.85f
        const val Z_LAYER_X = 1.35f

        const val CURTAIN_X0 = 2.2f
        const val CURTAIN_X1 = 2.47f
        const val CURTAIN_Y0 = 0.3f
        const val CURTAIN_Y1 = 2.2f
        const val CURTAIN_Z0 = -1.05f
        const val CURTAIN_Z1 = 0.85f

        const val SEAT_X = 0.75f
        const val SEAT_Y0 = 0.2f
        const val SEAT_Y1 = 0.6f
        const val SEAT_Z0 = -3.05f
        const val SEAT_Z1 = -2.66f

        /** The sofa's corner of the room, about a metre and a half across. */
        val CORNER = Box(-2.6f, -0.1f, -3.6f, -1.0f, 1.4f, -2.0f)

        /** The same corner, a metre across: a model under the size guard's 2 000 triangles. */
        val SMALL_CORNER = Box(-2.6f, -0.1f, -3.6f, -1.6f, 0.9f, -2.6f)

        /** The most a finished `.glb` weighs. */
        const val MAX_GLB_BYTES = 8 * 1024 * 1024

        /** [RealScanRoom.poses]' photos of the walls x = ±2.5, the room's long walls. */
        val LONG_WALL_PHOTOS = 20..47

        /** Under this share of the room photographed, a model is only partly seen. */
        const val PARTLY_SEEN = 0.5f

        /** A small room's raw surface: about 0.6 MB as a `.glb`, under its textured model's weight. */
        const val SMALL_ROOM_TRIANGLES = 20_000

        /** An atlas this much heavier takes a room's textured model past [MAX_GLB_BYTES], not past its raw surface. */
        const val HEAVY_ATLAS_BYTES = 7 * 1024 * 1024

        /** The finish's progress once the shape is simplified, and while cleaning. */
        const val TEXTURING = 0.6f
        const val CLEANING = 0.2f

        /**
         * The furnished room tiled to at least as many raw triangles as [CleanModel.maxRawTriangles]
         * lets a 192 MB heap mesh, past the biggest recordings' half million.
         */
        const val HEAP_RAW_TRIANGLES = 650_000

        /** The raw triangles the longest recording seen so far meshed to. */
        const val LONGEST_RECORDING_TRIANGLES = 533_000

        const val MB = 1024L * 1024L
        const val CHILD_TIMEOUT_S = 300L
        const val LOG_TAIL = 4000
    }
}

/**
 * The atlas as [JvmAtlasEncoder] writes it, [padding] bytes heavier: a texture that compresses as
 * badly as a noisy photo's, whatever its size. The bytes past the JPEG's end are not decoded.
 */
private class PaddedAtlasEncoder(private val padding: Int) : AtlasEncoder {
    override fun jpeg(argb: IntArray, width: Int, height: Int): ByteArray =
        JvmAtlasEncoder.jpeg(argb, width, height) + ByteArray(padding)
}

/**
 * The finish of a raw surface in a JVM of its own, its heap capped at a mid-range phone's
 * ([HEAP_MB], a Pixel 4a's `dalvik.vm.heapgrowthlimit`): [CleanModelRealScanTest]'s furnished
 * room, or that room tiled along x up to the triangles given, each copy with its photos — a scan
 * as large as a recording meshes to. What the result screen holds meanwhile stays live too:
 * [APP_BYTES] for the rest of the app, the recording's dense cloud ([CLOUD_POINTS_PER_TRIANGLE]
 * points per raw triangle, as the recordings have, within [DenseFusion.MAX_POINTS]), its TSDF
 * unless meshing let it go (half its budget or more, as the screen does), and the raw surface's
 * `.glb`, shown while the finish works in the surface's own arrays and lets them go — with its
 * ground when the screen reads one off it before it settles ([groundSource]). Then the swap
 * ([SWAP]): the finished model in the raw one's place, the raw one still held (it frames the
 * finished one), the finished `.glb` copied for the renderer (a direct buffer, in ART's Java
 * heap) and its ground read off it, all held through a full collection. Prints each stage as it
 * starts ([STAGE]), then [FINISHED] and its figures when it fits.
 */
object HeapCappedFinish {
    const val FINISHED = "FINISHED"
    const val RAW_GLB = "rawGlbBytes="
    const val STAGE = "STAGE "
    const val SWAP = "swap"
    const val SECOND_GLB = "secondRawGlbBytes="
    const val HEAP_MB = 192

    /** The finish's first run, the raw surface written as it is meshed; or [AGAIN]. */
    const val FIRST = "first"

    /** The finish run again on the raw surface a finish ran out of memory on, read back and shown. */
    const val AGAIN = "again"

    /** The furnished room as it is, not tiled. */
    const val ROOM = 0

    /** [CleanModel]'s stages, by the share of its progress each starts at. */
    private val STAGES = listOf("prepare" to 0f, "clean" to 0.1f, "simplify" to 0.4f, "texture" to 0.55f, "write" to 0.95f)
    private const val APP_BYTES = 32 * 1024 * 1024
    private const val BOUNDS = 6
    private const val CLOUD_POINTS_PER_TRIANGLE = 0.85f
    private const val MB = 1024 * 1024
    private const val SAMPLE_MS = 20L
    private const val NANOS_PER_MS = 1_000_000L

    /** The copies of the room stand side by side along x, further apart than it is wide. */
    private const val PITCH_M = 5.6f

    /** [args]: the raw surface's triangles, [ROOM] for the furnished room's own; then [FIRST] or [AGAIN]. */
    @JvmStatic
    fun main(args: Array<String>) {
        val app = ByteArray(APP_BYTES)
        val scanned = scanned(args.firstOrNull()?.toInt() ?: ROOM)
        val again = args.getOrNull(1) == AGAIN
        val raw = scanned.surface
        val rawTriangles = raw.triangleCount
        val runtime = Runtime.getRuntime()
        val cloud = cloudOf(raw.mesh, minOf((rawTriangles * CLOUD_POINTS_PER_TRIANGLE).toInt(), DenseFusion.MAX_POINTS))
        // The raw surface on screen, and its ground if the screen reads one off it meanwhile: the
        // one meshed now, or, finishing again, the one kept with the scan, read back.
        val shownGlb = RerunMeshGlb.write(raw.mesh)
        val shownRaw = RerunScanModel(
            RerunScanModel.Kind.Mesh, shownGlb, FloatArray(BOUNDS), rawTriangles, raw.mesh.vertexCount, finished = false,
            outOfMemoryInHeap = runtime.maxMemory().takeIf { again },
        )
        // The .glb the finish works beside, as the screen's build hands it over: the one shown,
        // first or finishing again (buildRerunModel's `shown`).
        val rawGlb = shownGlb
        val rawGround = AtomicReference<Any?>()
        val decoding = groundSource(shownRaw, settled = false)?.let { model ->
            thread(isDaemon = true) { rawGround.set(groundOf(model.glb)) }
        }
        val peak = AtomicLong()
        thread(isDaemon = true) {
            while (true) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                peak.accumulateAndGet(used) { a, b -> maxOf(a, b) }
                Thread.sleep(SAMPLE_MS)
            }
        }
        var stage = 0
        println("$STAGE${STAGES[stage].first} ${System.currentTimeMillis()}")
        val start = System.nanoTime()
        val result = CleanModel.build(raw, rawGlb, scanned.photos, JvmAtlasEncoder) { done ->
            while (stage + 1 < STAGES.size && done >= STAGES[stage + 1].second) {
                stage++
                println("$STAGE${STAGES[stage].first} ${System.currentTimeMillis()}")
            }
        }
        val ms = (System.nanoTime() - start) / NANOS_PER_MS
        decoding?.join()
        // The swap: everything the screen then holds, held at once (the heap collects before it gives up).
        println("$STAGE$SWAP ${System.currentTimeMillis()}")
        val finished = RerunScanModel(
            RerunScanModel.Kind.Mesh, result.glb, FloatArray(BOUNDS), maxOf(result.stats.finalTriangles, 1), 0,
            finished = !result.stats.rawKept,
            outOfMemoryInHeap = runtime.maxMemory().takeIf { result.stats.rawKept },
        )
        val rendered = result.glb.copyOf()
        val ground = groundSource(finished, settled = true)?.let { groundOf(it.glb) }
        println(
            "held at the swap: raw ${shownRaw.glb.size / MB} MB, finished ${finished.glb.size / MB} MB, " +
                "its copy ${rendered.size / MB} MB, ground ${ground != null}, raw ground ${rawGround.get() != null}",
        )
        // Out of memory, the finish gives the raw surface back: that is no finish within the heap.
        println(
            "${if (result.stats.rawKept) "RAW SURFACE KEPT, out of memory in ${result.stats.outOfMemoryIn}:" else FINISHED} $rawTriangles -> ${result.stats.finalTriangles} triangles, " +
                "textured ${result.stats.texture != null}, glb ${result.glb.size / MB.toFloat()} MB (raw ${rawGlb.size / MB.toFloat()} MB), " +
                "peak used ${peak.get() / MB} MB of $HEAP_MB, $ms ms; held: app ${app.size / MB} MB, " +
                "tsdf ${scanned.tsdfBytes / MB} MB ${if (scanned.tsdf != null) "held" else "let go"}, cloud ${cloud.count} points; " +
                "$RAW_GLB${shownGlb.size} $SECOND_GLB${if (rawGlb === shownGlb) 0 else rawGlb.size}",
        )
        System.out.flush()
        exitProcess(0)
    }

    /**
     * The ground the result screen reads off a model's `.glb` (`groundOf` in RerunModelUi.kt):
     * the ground and its meshes, `null` when there is none.
     */
    private fun groundOf(glb: ByteArray): Pair<ScanGround, ScanGroundMeshes>? = runCatching {
        val mesh = RerunGlbMesh.read(glb) ?: return@runCatching null
        val ground = ScanGround.of(mesh.positions, mesh.normals, mesh.vertexCount) ?: return@runCatching null
        ground to ScanGroundMeshes.of(ground)
    }.getOrNull()

    /** A raw surface, its photos, and its TSDF if the screen keeps it, with its size. */
    private class Scanned(val surface: RawSurface, val photos: ModelPhotos, val tsdf: RerunTsdf?, val tsdfBytes: Long)

    /**
     * The furnished room, tiled up to [triangles] if it has fewer, with its TSDF unless the
     * screen lets it go once meshed (half the app's budget, [RerunTsdf.MAX_BYTES], or more) —
     * copies of the room take copies of its TSDF. In a function of its own, so nothing in
     * `main`'s frame holds what is let go.
     */
    private fun scanned(triangles: Int): Scanned {
        val scan = RealScanRoom.scan(furnished = true)
        val photos = RealScanRoom.modelPhotos(furnished = true)
        val room = scan.mesh.triangleCount
        val copies = if (triangles > room) (triangles + room - 1) / room else 1
        val tsdfBytes = scan.tsdf.bytes * copies
        val tsdf = scan.tsdf.takeIf { tsdfBytes < RerunTsdf.MAX_BYTES / 2 }
        if (copies == 1) return Scanned(RawSurface(scan.mesh), photos, tsdf, tsdfBytes)
        return Scanned(RawSurface(tiled(scan.mesh, triangles)), tiled(photos, copies), tsdf, tsdfBytes)
    }

    /** The first [triangles] of copies of [mesh] side by side, [PITCH_M] apart along x. */
    private fun tiled(mesh: RerunMesh, triangles: Int): RerunMesh {
        val copies = (triangles + mesh.triangleCount - 1) / mesh.triangleCount
        val remap = IntArray(copies * mesh.vertexCount) { -1 }
        val indices = IntArray(triangles * 3)
        var vertices = 0
        for (t in 0 until triangles) {
            val copy = t / mesh.triangleCount
            val local = t % mesh.triangleCount
            for (k in 0..2) {
                val key = copy * mesh.vertexCount + mesh.indices[local * 3 + k]
                if (remap[key] < 0) remap[key] = vertices++
                indices[t * 3 + k] = remap[key]
            }
        }
        val positions = FloatArray(vertices * 3)
        val normals = FloatArray(vertices * 3)
        val colors = IntArray(vertices)
        for (key in remap.indices) {
            val v = remap[key]
            if (v < 0) continue
            val copy = key / mesh.vertexCount
            val s = key % mesh.vertexCount
            positions[v * 3] = mesh.positions[s * 3] + copy * PITCH_M
            positions[v * 3 + 1] = mesh.positions[s * 3 + 1]
            positions[v * 3 + 2] = mesh.positions[s * 3 + 2]
            mesh.normals.copyInto(normals, v * 3, s * 3, s * 3 + 3)
            colors[v] = mesh.colors[s]
        }
        return RerunMesh(positions, normals, colors, indices)
    }

    /** [photos] taken again in each of [copies] copies of the room, [PITCH_M] apart along x. */
    private fun tiled(photos: ModelPhotos, copies: Int): ModelPhotos =
        ModelPhotos(
            photos.lens,
            (0 until copies).flatMap { copy ->
                photos.photos.map { photo ->
                    val pose = photo.pose.copy(x = photo.pose.x + copy * PITCH_M)
                    ModelPhoto(pose) { photo.load()?.let { BakePhoto(pose, it.width, it.height, it.argb) } }
                }
            },
        )

    /** A dense cloud of [points] points on [mesh]'s vertices, as large as a recording's. */
    private fun cloudOf(mesh: RerunMesh, points: Int): DenseCloud {
        val positions = FloatArray(points * 3)
        val normals = FloatArray(points * 3)
        val colors = IntArray(points)
        for (p in 0 until points) {
            val v = p % mesh.vertexCount
            mesh.positions.copyInto(positions, p * 3, v * 3, v * 3 + 3)
            mesh.normals.copyInto(normals, p * 3, v * 3, v * 3 + 3)
            colors[p] = mesh.colors[v]
        }
        return DenseCloud(positions, colors, normals, ByteArray(points), ByteArray(points))
    }
}
