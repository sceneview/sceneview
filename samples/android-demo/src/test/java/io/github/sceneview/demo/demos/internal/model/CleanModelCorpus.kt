package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.RAW_FIRST_TRIANGLES
import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.OpenedCapture
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.RerunResultView
import io.github.sceneview.demo.demos.internal.RerunRrdReader
import io.github.sceneview.demo.demos.internal.RerunScanFile
import io.github.sceneview.demo.demos.internal.RerunScanModel
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.RerunTsdf
import io.github.sceneview.demo.demos.internal.SvpcCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * One recording through the finished-model pipeline, as the result screen builds a kept scan's
 * model — its dense surfels fused into a TSDF, meshed, then cleaned and textured from its key
 * photos — next to the raw model the app showed before. Writes, in [out]:
 * `before.glb`, `after.glb`, `before.png`, `after.png` and `stats.json`. A finish that runs out
 * of memory writes the raw surface as `after.glb`, as the app keeps it (`raw_kept`).
 */
internal object CleanModelCorpus {
    val EXTENSIONS = setOf(RerunScanFile.EXTENSION, "rrd")

    /** A scan of the corpus: a `*.svscan` or `*.rrd` file, or a session folder as the app keeps it. */
    fun isScan(file: File): Boolean = if (file.isDirectory) {
        File(file, RerunCapturePack.MANIFEST).isFile
    } else {
        file.isFile && file.extension.lowercase() in EXTENSIONS
    }

    /** What [file] gave: its figures, as written to `stats.json`. [progress] gets the finish's. */
    fun run(file: File, out: File, progress: (Float) -> Unit = {}): JsonObject {
        out.mkdirs()
        val stats = runCatching { measure(file, out, progress) }.getOrElse { failure ->
            buildJsonObject {
                put("file", file.name)
                put("error", "${failure::class.simpleName}: ${failure.message}")
            }
        }
        File(out, "stats.json").writeText(stats.toString())
        return stats
    }

    private fun measure(file: File, out: File, progress: (Float) -> Unit): JsonObject {
        val pack = read(file)
        val capture = checkNotNull(pack.open()) { "unreadable manifest" }
        val cloud = denseCloud(capture)
        if (cloud?.normals == null) {
            // The app meshes a kept scan from its surfels; without them it shows the points.
            return buildJsonObject {
                put("file", file.name)
                put("skipped", "no dense cloud with normals: the app shows this scan as points")
                put("photos", capture.trace.imageCount)
            }
        }
        val meshed = mesh(cloud)
        val raw = meshed.surface
        check(raw.triangleCount > 0) { "the surfels mesh to no surface" }
        val beforeStart = System.nanoTime()
        val before = RerunMeshGlb.write(raw.mesh)
        val beforeWriteMs = since(beforeStart)
        val rawBounds = raw.mesh.bounds()
        val rawTriangles = raw.triangleCount
        val rawVertices = raw.vertexCount
        File(out, "before.glb").writeBytes(before)
        thumbnail(before, rawBounds, rawTriangles, rawVertices)?.let { File(out, "before.png").writeBytes(it) }

        // The floor as scanned, measured before a big scan's finish takes its raw surface over.
        val rawFloor = FloorArea.of(raw.mesh.positions, raw.mesh.indices)

        // As the result screen finishes it: a big scan handed over and let go, its .glb written first.
        val photos = ModelPhotos.of(capture.trace, capture.manifest.lens) { path -> decode(capture, path) }
        val result = if (rawTriangles >= RAW_FIRST_TRIANGLES) {
            CleanModel.build(raw, before, photos, JvmAtlasEncoder, progress = progress)
        } else {
            CleanModel.build(raw.mesh, photos, JvmAtlasEncoder, rawGlb = before, progress = progress)
        }
        val s = result.stats
        File(out, "after.glb").writeBytes(result.glb)
        val after = result.model?.asRerunMesh()
        val afterThumbnail = if (after != null) {
            thumbnail(result.glb, after.bounds(), after.triangleCount, after.vertexCount)
        } else {
            thumbnail(result.glb, rawBounds, rawTriangles, rawVertices)
        }
        afterThumbnail?.let { File(out, "after.png").writeBytes(it) }
        val problems = GlbCheck.problems(result.glb)
        val finalFloor = result.model?.takeIf { rawFloor.area > 0f }?.let {
            FloorArea.of(it.positions, it.texturedIndices + it.plainIndices, rawFloor.height)
        }
        return buildJsonObject {
            put("file", file.name)
            put("dense_points", cloud.count)
            put("photos", capture.trace.imageCount)
            put("fuse_ms", meshed.fuseMs)
            put("mesh_ms", meshed.meshMs)
            put(
                "before",
                buildJsonObject {
                    put("triangles", rawTriangles)
                    put("vertices", rawVertices)
                    put("glb_bytes", before.size)
                    put("components", s.geometry.piecesIn)
                    put("extracted_triangles", meshed.extractedTriangles)
                    put("max_triangles", meshed.maxTriangles)
                    put("capped", meshed.capped)
                    put("write_ms", beforeWriteMs)
                    put("ms", meshed.fuseMs + meshed.meshMs + beforeWriteMs)
                },
            )
            put(
                "after",
                buildJsonObject {
                    put("triangles", s.finalTriangles)
                    put("vertices", s.finalVertices)
                    put("glb_bytes", s.glbBytes)
                    put("glb_limit_bytes", s.glbLimitBytes)
                    put("glb_guarded", s.glbGuarded)
                    put("atlas_halvings", s.atlasHalvings)
                    put("raw_kept", s.rawKept)
                    put("out_of_memory_in", s.outOfMemoryIn?.name?.lowercase())
                    put("components", s.geometry.piecesOut)
                    put("atlas", if (s.atlasWidth > 0) "${s.atlasWidth}x${s.atlasHeight}" else "none")
                    put("clean_input_triangles", s.cleanInputTriangles)
                    put("simplify_target", s.simplifyTarget)
                    s.droppedTextureShare?.let { put("dropped_texture_share", it) }
                    // The share of the model the photos texture, none when its texture was dropped.
                    put("textured_share", s.texture?.texturedShare ?: 0f)
                    // The finished floor's area over the raw one's, and the holes it has more.
                    finalFloor?.let { floor ->
                        put("floor_area_ratio", floor.area / rawFloor.area)
                        put("floor_holes_added", floor.innerLoops - rawFloor.innerLoops)
                    }
                    put("prepare_ms", s.prepareMs)
                    put("clean_ms", s.cleanMs)
                    put("simplify_ms", s.simplifyMs)
                    put("texture_ms", s.textureMs)
                    put("write_ms", s.writeMs)
                    put("pipeline_ms", s.totalMs)
                    put("ms", meshed.fuseMs + meshed.meshMs + s.totalMs)
                    put("glb_problems", problems.joinToString("; "))
                },
            )
            put(
                "geometry",
                buildJsonObject {
                    put("components_dropped", s.geometry.piecesDropped)
                    put("doubled_triangles", s.geometry.doubledTriangles)
                    put("planes", s.geometry.planes)
                    put("flat_share", s.geometry.flatShare)
                    put("holes_filled", s.geometry.holesFilled)
                },
            )
            s.texture?.let { t ->
                put(
                    "texture",
                    buildJsonObject {
                        put("photos_used", t.photosUsed)
                        put("charts", t.charts)
                        put("textured_share", t.texturedShare)
                        put("gain_min", t.gainMin)
                        put("gain_max", t.gainMax)
                        put("seam_raw", t.seamRaw)
                        put("seam_exposed", t.seamExposed)
                        put("seam_final", t.seamFinal)
                        put("texels_per_metre", t.texelsPerMetre)
                    },
                )
            }
        }
    }

    /** A scan's raw surface, how many triangles its extraction gave and could give, and each step's time. */
    private class Meshed(
        val surface: RawSurface,
        val extractedTriangles: Int,
        val maxTriangles: Int,
        val capped: Boolean,
        val fuseMs: Long,
        val meshMs: Long,
    )

    /**
     * [cloud] fused into a TSDF and meshed, as many triangles as this heap finishes
     * ([CleanModel.maxRawTriangles]). In a function of its own, as the result screen's: nothing
     * holds the TSDF past it, so the finish has its heap.
     */
    private fun mesh(cloud: DenseCloud): Meshed {
        val fuseStart = System.nanoTime()
        val tsdf = RerunTsdf().apply { integrateSurfels(cloud) }
        val fuseMs = since(fuseStart)
        val meshStart = System.nanoTime()
        val maxTriangles = CleanModel.maxRawTriangles(Runtime.getRuntime().maxMemory())
        val extraction = RerunMarchingCubes.extractWhole(tsdf, RerunMarchingCubes.DEFAULT_MIN_WEIGHT, maxTriangles)
        return Meshed(
            RawSurface(extraction.mesh),
            extraction.rawTriangles,
            maxTriangles,
            extraction.capped,
            fuseMs,
            since(meshStart),
        )
    }

    private fun read(file: File): RerunCapturePack {
        if (file.isDirectory) {
            // A session folder as the app keeps it (`files/rerun/sessions/<id>/`), read as the app reads it.
            return checkNotNull(RerunSessionStore(file.parentFile).capture(file.name)) { "incomplete session folder" }
        }
        val bytes = file.readBytes()
        return when (file.extension.lowercase()) {
            RerunScanFile.EXTENSION -> checkNotNull(RerunScanFile.read(bytes)) { "not a scan file" }
            else -> RerunRrdReader.recording(bytes, JvmRerunImageCodec).pack
        }
    }

    private fun denseCloud(capture: OpenedCapture): DenseCloud? {
        val dense = capture.manifest.dense ?: return null
        val blob = capture.bytesOf(dense.path) ?: return null
        return SvpcCodec.decode(blob)?.takeIf { it.count > 0 }
    }

    private fun decode(capture: OpenedCapture, path: String): BakePhoto? {
        val image = capture.bytesOf(path)?.let(JvmImages::decode) ?: return null
        return BakePhoto(ORIGIN, image.width, image.height, image.argb)
    }

    /** The model as the export thumbnail draws it (vertex colours, the result screen's view). */
    private fun thumbnail(glb: ByteArray, bounds: FloatArray, triangles: Int, vertices: Int): ByteArray? {
        val model = RerunScanModel(RerunScanModel.Kind.Mesh, glb, bounds, triangles, vertices)
        val argb = RerunResultView.thumbnail(model, THUMBNAIL_PX, THUMBNAIL_FOV_DEGREES) ?: return null
        return JvmImages.png(argb, THUMBNAIL_PX, THUMBNAIL_PX)
    }

    private fun since(start: Long): Long = (System.nanoTime() - start) / NANOS_PER_MS

    private val ORIGIN = DebugPose(0f, 0f, 0f)
    private const val THUMBNAIL_PX = 512
    private const val THUMBNAIL_FOV_DEGREES = 45.0
    private const val NANOS_PER_MS = 1_000_000L
}
