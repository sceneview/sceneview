package io.github.sceneview.ar.depth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The ML depth scale fit against the vectors iOS reads too
 * (`SceneViewSwift/Tests/SceneViewSwiftTests/Resources/ml-depth-fit-vectors.json`, checked by
 * `MLDepthFitTests.testSharedVectors`). Same file, same tolerances: a change to either fit that
 * moves one platform away from the other fails here or there.
 */
class DepthScaleFitSharedVectorsTest {

    private val cases: List<JsonObject> by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream(VECTORS)
        assertNotNull("$VECTORS missing from the test classpath", stream)
        val text = stream!!.bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
    }

    @Test
    fun `every shared vector gives the expected fit`() {
        assertTrue("expected at least 7 cases, got ${cases.size}", cases.size >= 7)
        for (case in cases) check(case)
    }

    private fun check(case: JsonObject) {
        val name = case.getValue("name").jsonPrimitive.content
        val anchors = case.getValue("anchors").jsonArray.map { it.jsonObject }
        val d = FloatArray(anchors.size) { anchors[it].getValue("d").jsonPrimitive.float }
        val z = FloatArray(anchors.size) { anchors[it].getValue("z").jsonPrimitive.float }
        val confidence = FloatArray(anchors.size) {
            anchors[it]["confidence"]?.jsonPrimitive?.floatOrNull ?: 1f
        }
        val prior = (case["prior"] as? JsonObject)?.let {
            DepthScale(
                scale = it.getValue("scale").jsonPrimitive.double,
                offset = it.getValue("shift").jsonPrimitive.double,
                inliers = 0,
                zMin = 0f,
                zMax = 0f,
                rmsRelativeError = 0.0,
            )
        }
        val expect = case.getValue("expect").jsonObject
        val result = DepthScaleFit.fit(d, z, confidence, anchors.size, prior)

        if (!expect.getValue("valid").jsonPrimitive.booleanOrNull!!) {
            assertTrue("$name: expected no fit, got $result", result is DepthScaleFit.Result.Rejected)
            return
        }
        assertTrue("$name: expected a fit, got $result", result is DepthScaleFit.Result.Fit)
        val fit = (result as DepthScaleFit.Result.Fit).scale
        val tolerance = expect["tolerance"]?.jsonPrimitive?.doubleOrNull ?: DEFAULT_TOLERANCE

        expect["scale"]?.jsonPrimitive?.doubleOrNull?.let { scale ->
            assertEquals("$name scale", scale, fit.scale, scale * tolerance + 1e-5)
        }
        expect["shift"]?.jsonPrimitive?.doubleOrNull?.let { shift ->
            assertEquals("$name shift", shift, fit.offset, max(abs(shift), 0.05) * tolerance * 4 + 1e-4)
        }
        expect["minInliers"]?.jsonPrimitive?.intOrNull?.let { minimum ->
            assertTrue("$name inliers ${fit.inliers} < $minimum", fit.inliers >= minimum)
        }
        expect["maxInliers"]?.jsonPrimitive?.intOrNull?.let { maximum ->
            assertTrue("$name inliers ${fit.inliers} > $maximum", fit.inliers <= maximum)
        }
        val scale = expect["scale"]?.jsonPrimitive?.doubleOrNull
        val betweenDataAndPrior = expect["scaleBetweenDataAndPrior"]?.jsonPrimitive?.booleanOrNull == true
        if (betweenDataAndPrior && prior != null && scale != null) {
            assertTrue("$name pulled by prior", fit.scale > min(scale, prior.scale))
            assertTrue("$name not past the prior", fit.scale < max(scale, prior.scale))
        }
    }

    private companion object {
        const val VECTORS = "ml-depth-fit-vectors.json"
        const val DEFAULT_TOLERANCE = 0.001
    }
}
