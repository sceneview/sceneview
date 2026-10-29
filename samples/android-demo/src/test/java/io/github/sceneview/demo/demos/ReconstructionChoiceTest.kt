package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.ReconstructionBench
import io.github.sceneview.demo.demos.internal.ReconstructionMethod
import io.github.sceneview.demo.demos.internal.RerunTsdf
import io.github.sceneview.demo.demos.internal.RerunTsdfTest.Companion.fibonacciSphere
import io.github.sceneview.demo.demos.internal.ScreenedPoisson
import io.github.sceneview.demo.demos.internal.Vec3
import io.github.sceneview.demo.demos.internal.model.Box
import io.github.sceneview.demo.demos.internal.model.CleanScenes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The end of a scan asks how to finish its model before building it (#4630): what "Filled" can
 * make of the scan within the fill's 40 MB, told before it is picked; the answer, a pick or a
 * dismissal ("As scanned"); and the model built by the method picked. Robolectric for the
 * build's `Log` lines and the texture's bitmaps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconstructionChoiceTest {

    @Test
    fun `the model is built by the method picked`() {
        val box = Box(0f, 0f, 0f, 1.2f, 0.9f, 1.0f)
        val tsdf = CleanScenes.fuse(Box(-0.2f, -0.2f, -0.2f, 1.4f, 1.1f, 1.2f), NOISE_M, NOISE_M) { x, y, z ->
            box.sdf(x, y, z)
        }
        // The fused depth holds the box; the surfels, a ball well away from it.
        val source = RerunModelSource.Live(tsdf, ball())
        val filled = assertNotNull(buildScanModel(source, reconstruction = ReconstructionMethod.Poisson) {})
        assertCentre("filled", BALL_CENTRE, filled.bounds)
        val fused = assertNotNull(buildScanModel(source, reconstruction = ReconstructionMethod.Fused) {})
        assertCentre("as scanned", Vec3(0.6f, 0.45f, 0.5f), fused.bounds)
        val unasked = assertNotNull(buildScanModel(source) {})
        assertCentre("by default", Vec3(0.6f, 0.45f, 0.5f), unasked.bounds)
    }

    @Test
    fun `a scan without surfels with normals has nothing to fill`() {
        val ball = ball()
        assertEquals(FillPlan.NoSurfels, fillPlan(RerunModelSource.Live(RerunTsdf(), cloud = null)))
        assertEquals(FillPlan.NoSurfels, fillPlan(RerunModelSource.Live(RerunTsdf(), DenseCloud(ball.positions, ball.colors))))
        assertEquals(FillPlan.NoSurfels, fillPlan(RerunModelSource.Surfels(DenseCloud(FloatArray(0), IntArray(0), FloatArray(0)))))
    }

    @Test
    fun `filled tells the detail it fills at, and when nothing fits the budget`() {
        val ball = ball()
        assertEquals(FillPlan.Fills(1), fillPlan(RerunModelSource.Surfels(ball)))
        assertEquals(FillPlan.Fills(1), fillPlan(RerunModelSource.Live(RerunTsdf(), ball)))
        val full = assertNotNull(ScreenedPoisson.reconstruct(ball))
        assertEquals(FillPlan.Fills(2), fillPlan(RerunModelSource.Surfels(ball), maxBytes = full.solveBytes - 1))
        assertEquals(FillPlan.TooBig, fillPlan(RerunModelSource.Surfels(ball), maxBytes = 1024))
    }

    @Test
    fun `the whole synthetic room fills at half the detail within the budget`() {
        // The full walk's 220,000 surfels do not fit 40 MB on the 2.5 cm grid: the sheet says so.
        assertEquals(FillPlan.Fills(2), fillPlan(RerunModelSource.Surfels(ReconstructionBench.FULL.cloud())))
    }

    @Test
    fun `not asked, the model finishes as scanned and nothing is shown`() = runTest {
        val shown = ArrayList<FinishQuestion?>()
        assertEquals(ReconstructionMethod.Fused, finishMethod(RerunModelSource.Surfels(ball()), asks = false) { shown += it })
        assertTrue("shown $shown", shown.isEmpty())
    }

    @Test
    fun `with nothing to fill, the model finishes as scanned without asking`() = runTest {
        val shown = ArrayList<FinishQuestion?>()
        assertEquals(ReconstructionMethod.Fused, finishMethod(RerunModelSource.Live(RerunTsdf(), cloud = null), asks = true) { shown += it })
        assertTrue("shown $shown", shown.isEmpty())
    }

    @Test
    fun `asked, the model finishes with the method picked, and the question goes`() = runTest {
        val shown = ArrayList<FinishQuestion?>()
        val method = finishMethod(RerunModelSource.Surfels(ball()), asks = true) { question ->
            shown += question
            question?.finish(ReconstructionMethod.Poisson)
        }
        assertEquals(ReconstructionMethod.Poisson, method)
        assertEquals(2, shown.size)
        assertEquals(FillPlan.Fills(1), assertNotNull(shown.first()).fill)
        assertNull("the question still shown", shown.last())
    }

    @Test
    fun `dismissed, the model finishes as scanned`() = runTest {
        val shown = ArrayList<FinishQuestion?>()
        val method = finishMethod(RerunModelSource.Surfels(ball()), asks = true) { question ->
            shown += question
            question?.dismiss()
        }
        assertEquals(ReconstructionMethod.Fused, method)
        assertNull("the question still shown", shown.last())
    }

    @Test
    fun `a scan too big to fill is still asked, so the sheet says why`() = runTest {
        val shown = ArrayList<FinishQuestion?>()
        val method = finishMethod(RerunModelSource.Surfels(ball()), asks = true, maxBytes = 1024) { question ->
            shown += question
            question?.dismiss()
        }
        assertEquals(ReconstructionMethod.Fused, method)
        assertEquals(FillPlan.TooBig, assertNotNull(shown.first()).fill)
        assertNull("the question still shown", shown.last())
    }

    @Test
    fun `only the first answer counts`() = runTest {
        val question = FinishQuestion(FillPlan.Fills(1))
        question.finish(ReconstructionMethod.Poisson)
        question.dismiss()
        assertEquals(ReconstructionMethod.Poisson, question.await())
    }

    private fun assertCentre(label: String, expected: Vec3, bounds: FloatArray) {
        val centre = Vec3((bounds[0] + bounds[3]) / 2f, (bounds[1] + bounds[4]) / 2f, (bounds[2] + bounds[5]) / 2f)
        assertTrue(
            "$label: centre $centre, expected $expected",
            abs(centre.x - expected.x) < NEAR_M && abs(centre.y - expected.y) < NEAR_M && abs(centre.z - expected.z) < NEAR_M,
        )
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

    /** One surfel per 2 cm square of a ball's surface, normals out, as [DenseFusion] leaves. */
    private fun ball(): DenseCloud {
        val count = (4.0 * Math.PI * RADIUS * RADIUS / (DenseFusion.VOXEL_M * DenseFusion.VOXEL_M)).toInt()
        val positions = FloatArray(count * 3)
        val normals = FloatArray(count * 3)
        fibonacciSphere(count).forEachIndexed { i, d ->
            val p = BALL_CENTRE + d * RADIUS
            positions[i * 3] = p.x
            positions[i * 3 + 1] = p.y
            positions[i * 3 + 2] = p.z
            normals[i * 3] = d.x
            normals[i * 3 + 1] = d.y
            normals[i * 3 + 2] = d.z
        }
        return DenseCloud(positions, IntArray(count) { GREY }, normals)
    }

    private companion object {
        const val NOISE_M = 0.002f
        const val RADIUS = 0.4f
        val BALL_CENTRE = Vec3(3.1f, 1.0f, 2.9f)
        const val NEAR_M = 0.15f
        const val GREY = 0xFF9E9E9E.toInt()
    }
}
