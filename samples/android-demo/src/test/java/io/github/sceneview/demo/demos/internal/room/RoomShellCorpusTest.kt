package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.depthModelOf
import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.RerunGlbMesh
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.RerunStoredSession
import io.github.sceneview.demo.demos.internal.ScanDepthFormat
import io.github.sceneview.demo.demos.internal.ScanScope
import io.github.sceneview.demo.demos.internal.model.CleanModelCorpus
import io.github.sceneview.demo.demos.internal.model.JvmAtlasEncoder
import io.github.sceneview.demo.demos.internal.model.JvmImages
import io.github.sceneview.demo.demos.internal.model.ModelPhotos
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The room shell of every recording of a corpus, as the result builds it:
 *
 * ```
 * SCENEVIEW_SCAN_CORPUS=/path/to/scans SCENEVIEW_SCAN_OUT=/path/to/out \
 *   ./gradlew :samples:android-demo:testDebugUnitTest --tests '*RoomShellCorpusTest*'
 * ```
 *
 * Skipped when either variable is unset. Writes, per scan, `room.glb`, each face's texture
 * (`tex_<face>.png`), the `.glb` drawn back as the result shows it from four dollhouse corners
 * (`doll0.png` … `doll3.png`) and from above (`top.png`), and `room.json`, its figures.
 *
 * A session that kept its depth recording has its depth fused again ([RoomDepthMesh]): the whole
 * surface drawn on its own (`fused0.png` … `fused3.png`, `fused_top.png`) — a scan without a room
 * box too — and the shell's objects are that surface's (the dollhouse views above), next to the
 * shell its points would give (`points0.png`). The fusion's timings, blocks and triangles go in
 * `room.json`. Folders named `out…` under the corpus are outputs, never scans.
 */
class RoomShellCorpusTest {
    @Test
    fun `every scan of the corpus is built as a room shell`() {
        val corpus = System.getenv(CORPUS)?.let(::File)
        val out = System.getenv(OUT)?.let(::File)
        assumeTrue("$CORPUS and $OUT not set", corpus != null && out != null)
        val scans = checkNotNull(corpus).walkTopDown()
            .onEnter { it == corpus || !it.name.startsWith("out") }
            .filter(CleanModelCorpus::isScan)
            .sortedBy { it.path }
            .toList()
        assumeTrue("no scan in $corpus", scans.isNotEmpty())
        scans.forEach { scan -> println(run(scan, File(checkNotNull(out), scan.name))) }
    }

    private fun run(file: File, out: File): JsonObject {
        out.mkdirs()
        val stats = runCatching { measure(file, out) }.getOrElse { failure ->
            buildJsonObject {
                put("file", file.name)
                put("error", "${failure::class.simpleName}: ${failure.message}")
            }
        }
        File(out, "room.json").writeText(stats.toString())
        return stats
    }

    private fun measure(file: File, out: File): JsonObject {
        val capture = checkNotNull(CleanModelCorpus.read(file).open()) { "unreadable manifest" }
        val cloud = checkNotNull(CleanModelCorpus.denseCloud(capture)) { "no dense cloud" }
        val photos = ModelPhotos.of(capture.trace, capture.manifest.lens) { path ->
            capture.bytesOf(path)?.let(JvmImages::decode)?.let { BakePhoto(ORIGIN, it.width, it.height, it.argb) }
        }
        val fused = if (file.isDirectory) RoomDepthMesh.fuseSession(file, tuning()) else null
        fused?.let {
            drawFused(it.mesh, out)
            File(out, "fused.glb").writeBytes(RerunMeshGlb.write(it.mesh))
        }
        val started = System.nanoTime()
        val shell = runBlocking { RoomShellBuilder.build(cloud, photos, capture.manifest.floorY, depthMesh = fused?.mesh) }
            ?: return buildJsonObject {
                put("file", file.name)
                put("shell", "none: no convincing room box")
                fused?.let { put("depth", depthJson(it)) }
                if (fused != null) put("model", modelJson(file, cloud, capture.manifest.floorY, out))
            }
        val buildMs = (System.nanoTime() - started) / NANOS_PER_MS
        val glb = checkNotNull(RoomShellGlb.write(shell.faces, JvmAtlasEncoder, objects = shell.objectMesh)) { "glb not written" }
        File(out, "room.glb").writeBytes(glb)
        shell.faces.forEach { face -> File(out, "tex_${face.name}.png").writeBytes(JvmImages.png(face.argb, face.columns, face.rows)) }
        val mesh = ShellRender.read(glb)
        AZIMUTHS.forEachIndexed { i, azimuth ->
            val image = ShellRender.view(mesh, shell.objects, shell.box, azimuth)
            File(out, "doll$i.png").writeBytes(JvmImages.png(image.argb, image.width, image.height))
        }
        val top = ShellRender.view(mesh, shell.objects, shell.box, null)
        File(out, "top.png").writeBytes(JvmImages.png(top.argb, top.width, top.height))
        if (fused != null) {
            // The same shell with its objects as points: what a scan without its depth shows.
            val points = checkNotNull(runBlocking { RoomShellBuilder.build(cloud, photos, capture.manifest.floorY) })
            val pointsMesh = ShellRender.read(checkNotNull(RoomShellGlb.write(points.faces, JvmAtlasEncoder)))
            val image = ShellRender.view(pointsMesh, points.objects, points.box, AZIMUTHS[0])
            File(out, "points0.png").writeBytes(JvmImages.png(image.argb, image.width, image.height))
        }
        val s = shell.stats
        return buildJsonObject {
            put("file", file.name)
            put("box", shell.box.toString())
            put("floor_y", shell.box.floorY)
            put("ceiling_y", shell.box.ceilingY)
            put("plan", shell.plan?.toString() ?: "box")
            val cameras = photos?.photos.orEmpty().flatMap { listOf(it.pose.x, it.pose.y, it.pose.z) }.toFloatArray()
            put("cells", RoomPlan.cells(cloud, shell.box, cameras)?.toString() ?: "no peak inside the box")
            put("points", s.points)
            put("clean_points", s.cleanPoints)
            put("object_points", s.objectPoints)
            put("objects_recoloured", s.objectsRecoloured)
            put("object_triangles", s.objectTriangles)
            fused?.let { put("depth", depthJson(it)) }
            put("photos", s.photosIn)
            put("photos_used", s.photosUsed)
            put("texels", s.texels)
            put("seen_share", s.seenShare)
            shell.faces.forEach { put("seen_${it.name}", it.seenShare) }
            put("glb_bytes", glb.size)
            put("box_ms", s.boxMs)
            put("neighbours_ms", s.neighboursMs)
            put("photos_ms", s.photosMs)
            put("finish_ms", s.finishMs)
            put("build_ms", buildMs)
            put("cores", Runtime.getRuntime().availableProcessors())
        }
    }

    /** The fusion's settings, each overridable by `SCENEVIEW_DEPTH_<NAME>` to tune them on the corpus. */
    private fun tuning(): RoomDepthMesh.Settings {
        fun env(name: String) = System.getenv("SCENEVIEW_DEPTH_$name")
        val d = RoomDepthMesh.Settings()
        return d.copy(
            voxelM = env("VOXEL")?.toFloat() ?: d.voxelM,
            truncationM = env("TRUNCATION")?.toFloat() ?: d.truncationM,
            minWeight = env("MIN_WEIGHT")?.toFloat() ?: d.minWeight,
            weakestCorner = env("WEAKEST")?.toFloat() ?: d.weakestCorner,
            holeSpanM = env("HOLE_SPAN")?.toFloat() ?: d.holeSpanM,
            minComponentTriangles = env("MIN_COMPONENT")?.toInt() ?: d.minComponentTriangles,
        )
    }

    /**
     * The result a scan with depth and no room box opens on, built by the app's own
     * [depthModelOf] — on a copy of the session, so the corpus is never written to — and drawn as
     * `model0.png` … `model3.png` and `model_top.png`: its figures.
     */
    private fun modelJson(session: File, cloud: DenseCloud, floorY: Float?, out: File): JsonObject {
        val scope = RerunStoredSession.parse(File(session, RerunSessionStore.INFO).readText())?.scope ?: ScanScope.Room
        val root = Files.createTempDirectory("depth-model").toFile()
        try {
            val store = RerunSessionStore(root)
            val copy = store.directoryOf(session.name).apply { mkdirs() }
            File(session, RerunSessionStore.INFO).copyTo(File(copy, RerunSessionStore.INFO))
            File(session, ScanDepthFormat.FILE).copyTo(File(copy, ScanDepthFormat.FILE))
            val started = System.nanoTime()
            val model = depthModelOf(store, session.name, scope, cloud, floorY)
            val buildMs = (System.nanoTime() - started) / NANOS_PER_MS
            model ?: return buildJsonObject { put("none", "no model for scope ${scope.key}") }
            val surface = checkNotNull(RerunGlbMesh.read(model.glb)) { "model glb not read back" }
            AZIMUTHS.forEachIndexed { i, azimuth ->
                val image = ShellRender.view(surface, azimuth)
                File(out, "model$i.png").writeBytes(JvmImages.png(image.argb, image.width, image.height))
            }
            val top = ShellRender.view(surface, null)
            File(out, "model_top.png").writeBytes(JvmImages.png(top.argb, top.width, top.height))
            val readStarted = System.nanoTime()
            val back = checkNotNull(depthModelOf(store, session.name, scope, cloud, floorY)) { "kept model not read back" }
            return buildJsonObject {
                put("scope", scope.key)
                put("triangles", model.triangles)
                put("vertices", model.vertices)
                put("glb_bytes", model.glb.size)
                put("build_ms", buildMs)
                put("read_back_ms", (System.nanoTime() - readStarted) / NANOS_PER_MS)
                put("read_back_same", back.glb.contentEquals(model.glb))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /** The whole fused surface on its own, from the four corners and from above. */
    private fun drawFused(surface: RerunMesh, out: File) {
        AZIMUTHS.forEachIndexed { i, azimuth ->
            val image = ShellRender.view(surface, azimuth)
            File(out, "fused$i.png").writeBytes(JvmImages.png(image.argb, image.width, image.height))
        }
        val top = ShellRender.view(surface, null)
        File(out, "fused_top.png").writeBytes(JvmImages.png(top.argb, top.width, top.height))
    }

    private fun depthJson(r: RoomDepthMesh.Result): JsonObject = buildJsonObject {
        put("frames", r.frames)
        put("fused_frames", r.fused)
        put("blocks", r.blocks)
        put("bytes", r.bytes)
        put("max_bytes", RoomDepthMesh.MAX_BYTES)
        put("dropped_blocks", r.droppedBlocks)
        put("raw_triangles", r.rawTriangles)
        put("triangles", r.mesh.triangleCount)
        put("vertices", r.mesh.vertexCount)
        put("dropped_components", r.droppedComponents)
        put("capped", r.capped)
        put("holes_closed", r.holesClosed)
        put("fuse_ms", r.fuseMs)
        put("mesh_ms", r.meshMs)
    }

    private companion object {
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"
        const val NANOS_PER_MS = 1_000_000L
        val ORIGIN = DebugPose(0f, 0f, 0f)
        val AZIMUTHS = listOf(35.0, 125.0, 215.0, 305.0)
    }
}
