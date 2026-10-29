package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.RerunModelSource
import io.github.sceneview.demo.demos.fillPlan
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.ReconstructionBench
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.RerunResultView
import io.github.sceneview.demo.demos.internal.RerunRrdReader
import io.github.sceneview.demo.demos.internal.RerunScanFile
import io.github.sceneview.demo.demos.internal.RerunScanModel
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.RerunTsdf
import io.github.sceneview.demo.demos.internal.ScreenedPoisson
import io.github.sceneview.demo.demos.internal.SvpcCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The end of a scan's two reconstructions on real scans, for a machine that has them:
 *
 * ```
 * SCENEVIEW_SCAN_CORPUS=/path/to/scans SCENEVIEW_SCAN_OUT=/path/to/out \
 *   ./gradlew :samples:android-demo:testDebugUnitTest --tests '*ReconstructionCorpusTest*'
 * ```
 *
 * Every scan of the corpus ([CleanModelCorpus.isScan]) with surfels gets a folder in the output:
 * `fused.glb` and `poisson.glb`, a thumbnail of each, and `reconstruction.json` (triangles, the
 * holes' rims, times, and the plan the end-of-scan sheet would show for "Filled" with the time it
 * took to work out); `summary.json` lists them all. Skipped without the two variables. The
 * second test runs the same code on the synthetic room, so it is known to work end to end.
 */
class ReconstructionCorpusTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `every scan of the corpus is reconstructed both ways`() {
        val corpus = System.getenv(CORPUS)?.let(::File)
        val out = System.getenv(OUT)?.let(::File)
        assumeTrue("$CORPUS and $OUT not set", corpus != null && out != null)
        val scans = checkNotNull(corpus).walkTopDown().filter(CleanModelCorpus::isScan).sortedBy { it.path }.toList()
        assumeTrue("no *.svscan, *.rrd or session folder in $corpus", scans.isNotEmpty())
        val results = scans.map { scan ->
            val target = File(checkNotNull(out), scan.nameWithoutExtension)
            runCatching { cloudOf(scan) }.fold(
                onSuccess = { cloud -> cloud?.let { run(scan.name, it, target) } ?: skipped(scan.name, "no surfels with normals") },
                onFailure = { skipped(scan.name, "${it::class.simpleName}: ${it.message}") },
            )
        }
        File(checkNotNull(out), "summary.json").writeText(results.joinToString(",\n", "[\n", "\n]\n"))
        results.forEach(::println)
    }

    @Test
    fun `the synthetic room goes through the corpus run end to end`() {
        val fusion = DenseFusion()
        TexturedSyntheticRoom.frames().forEach { fusion.add(DepthBackProjection.project(it)) }
        val out = folder.newFolder("synthetic")
        val stats = run("synthetic", fusion.cloud(minViews = DenseFusion.MIN_VIEWS), out)
        println(stats)
        for (name in listOf("fused", "poisson")) {
            assertEquals(emptyList<String>(), GlbCheck.problems(File(out, "$name.glb").readBytes()))
            assertTrue("$name.png", JvmImages.decode(File(out, "$name.png").readBytes()) != null)
        }
        val json = File(out, "reconstruction.json").readText()
        assertTrue(json, json.contains("poisson_triangles") && json.contains("fill_plan_ms"))
    }

    /** [cloud] reconstructed both ways into [out]; its figures, also written to `reconstruction.json`. */
    private fun run(name: String, cloud: DenseCloud, out: File): JsonObject {
        out.mkdirs()
        val planStart = System.nanoTime()
        val plan = fillPlan(RerunModelSource.Surfels(cloud))
        val planMs = since(planStart)
        val fuseStart = System.nanoTime()
        val fused = RerunMarchingCubes.extractWhole(RerunTsdf().apply { integrateSurfels(cloud) }).mesh
        val fusedMs = since(fuseStart)
        val poissonStart = System.nanoTime()
        val result = ScreenedPoisson.reconstruct(cloud)
        val poisson = result?.let { RerunMarchingCubes.extractWhole(it.tsdf).mesh }
        val poissonMs = since(poissonStart)
        write(fused, File(out, "fused"))
        poisson?.let { write(it, File(out, "poisson")) }
        val (fusedEdges, fusedRim) = ReconstructionBench.boundary(fused)
        val stats = buildJsonObject {
            put("file", name)
            put("surfels", cloud.count)
            put("fill_plan", plan.toString())
            put("fill_plan_ms", planMs)
            put("fused_triangles", fused.triangleCount)
            put("fused_boundary_edges", fusedEdges)
            put("fused_boundary_m", fusedRim)
            put("fused_ms", fusedMs)
            if (poisson != null && result != null) {
                val (edges, rim) = ReconstructionBench.boundary(poisson)
                put("poisson_triangles", poisson.triangleCount)
                put("poisson_boundary_edges", edges)
                put("poisson_boundary_m", rim)
                put("poisson_ms", poissonMs)
                put("poisson_grid_m", result.voxelM)
                put("poisson_nodes", result.nodes)
                put("poisson_iterations", result.iterations)
                put("poisson_residual", result.residual)
            } else {
                put("poisson_skipped", "no surfels with normals, or no grid within the budget")
            }
        }
        File(out, "reconstruction.json").writeText(stats.toString())
        return stats
    }

    /** [mesh] as `<base>.glb` and its thumbnail as `<base>.png`. */
    private fun write(mesh: RerunMesh, base: File) {
        val glb = RerunMeshGlb.write(mesh)
        File("${base.path}.glb").writeBytes(glb)
        val model = RerunScanModel(RerunScanModel.Kind.Mesh, glb, mesh.bounds(), mesh.triangleCount, mesh.vertexCount)
        RerunResultView.thumbnail(model, THUMBNAIL_PX, THUMBNAIL_FOV_DEGREES)?.let { argb ->
            File("${base.path}.png").writeBytes(JvmImages.png(argb, THUMBNAIL_PX, THUMBNAIL_PX))
        }
    }

    /** [file]'s surfels, read as the app reads a kept scan; `null` without them or their normals. */
    private fun cloudOf(file: File): DenseCloud? {
        val pack: RerunCapturePack = if (file.isDirectory) {
            checkNotNull(RerunSessionStore(file.parentFile).capture(file.name)) { "incomplete session folder" }
        } else {
            val bytes = file.readBytes()
            when (file.extension.lowercase()) {
                RerunScanFile.EXTENSION -> checkNotNull(RerunScanFile.read(bytes)) { "not a scan file" }
                else -> RerunRrdReader.recording(bytes, JvmRerunImageCodec).pack
            }
        }
        val capture = checkNotNull(pack.open()) { "unreadable manifest" }
        val dense = capture.manifest.dense ?: return null
        val blob = capture.bytesOf(dense.path) ?: return null
        return SvpcCodec.decode(blob)?.takeIf { it.count > 0 && it.normals != null }
    }

    private fun skipped(name: String, why: String): JsonObject = buildJsonObject {
        put("file", name)
        put("skipped", why)
    }

    private fun since(start: Long): Long = (System.nanoTime() - start) / NANOS_PER_MS

    private companion object {
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"
        const val THUMBNAIL_PX = 512
        const val THUMBNAIL_FOV_DEGREES = 45.0
        const val NANOS_PER_MS = 1_000_000L
    }
}
