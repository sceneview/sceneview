package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.DebugPose
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
 */
class RoomShellCorpusTest {
    @Test
    fun `every scan of the corpus is built as a room shell`() {
        val corpus = System.getenv(CORPUS)?.let(::File)
        val out = System.getenv(OUT)?.let(::File)
        assumeTrue("$CORPUS and $OUT not set", corpus != null && out != null)
        val scans = checkNotNull(corpus).walkTopDown().filter(CleanModelCorpus::isScan).sortedBy { it.path }.toList()
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
        val started = System.nanoTime()
        val shell = runBlocking { RoomShellBuilder.build(cloud, photos, capture.manifest.floorY) }
            ?: return buildJsonObject {
                put("file", file.name)
                put("shell", "none: no convincing room box")
            }
        val buildMs = (System.nanoTime() - started) / NANOS_PER_MS
        val glb = checkNotNull(RoomShellGlb.write(shell.faces, JvmAtlasEncoder)) { "glb not written" }
        File(out, "room.glb").writeBytes(glb)
        shell.faces.forEach { face -> File(out, "tex_${face.name}.png").writeBytes(JvmImages.png(face.argb, face.columns, face.rows)) }
        val mesh = ShellRender.read(glb)
        AZIMUTHS.forEachIndexed { i, azimuth ->
            val image = ShellRender.view(mesh, shell.objects, shell.box, azimuth)
            File(out, "doll$i.png").writeBytes(JvmImages.png(image.argb, image.width, image.height))
        }
        val top = ShellRender.view(mesh, shell.objects, shell.box, null)
        File(out, "top.png").writeBytes(JvmImages.png(top.argb, top.width, top.height))
        val s = shell.stats
        return buildJsonObject {
            put("file", file.name)
            put("box", shell.box.toString())
            put("plan", shell.plan?.toString() ?: "box")
            val cameras = photos?.photos.orEmpty().flatMap { listOf(it.pose.x, it.pose.y, it.pose.z) }.toFloatArray()
            put("cells", RoomPlan.cells(cloud, shell.box, cameras)?.toString() ?: "no peak inside the box")
            put("points", s.points)
            put("clean_points", s.cleanPoints)
            put("object_points", s.objectPoints)
            put("objects_recoloured", s.objectsRecoloured)
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

    private companion object {
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"
        const val NANOS_PER_MS = 1_000_000L
        val ORIGIN = DebugPose(0f, 0f, 0f)
        val AZIMUTHS = listOf(35.0, 125.0, 215.0, 305.0)
    }
}
