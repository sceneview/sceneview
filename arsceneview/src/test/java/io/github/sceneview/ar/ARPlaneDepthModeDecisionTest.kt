package io.github.sceneview.ar

import com.google.ar.core.Config
import org.junit.Assert.assertEquals
import org.junit.Test

class ARPlaneDepthModeDecisionTest {
    @Test
    fun `implicit depth follows support and plane opt-out`() {
        listOf(
            Triple(true, true, Config.DepthMode.AUTOMATIC),
            Triple(true, false, Config.DepthMode.DISABLED),
            Triple(false, true, Config.DepthMode.DISABLED),
            Triple(false, false, Config.DepthMode.DISABLED),
        ).forEach { (automaticDepthSupported, planeDepthOcclusion, expected) ->
            assertEquals(
                "supported=$automaticDepthSupported, planeDepthOcclusion=$planeDepthOcclusion",
                expected,
                resolvePlaneDepthMode(
                    requestedDepthMode = null,
                    automaticDepthSupported = automaticDepthSupported,
                    planeDepthOcclusion = planeDepthOcclusion,
                ),
            )
        }
    }

    @Test
    fun `every explicit depth mode wins across support and plane opt-out`() {
        Config.DepthMode.values().forEach { requestedDepthMode ->
            listOf(true, false).forEach { automaticDepthSupported ->
                listOf(true, false).forEach { planeDepthOcclusion ->
                    assertEquals(
                        "requested=$requestedDepthMode, supported=$automaticDepthSupported, " +
                            "planeDepthOcclusion=$planeDepthOcclusion",
                        requestedDepthMode,
                        resolvePlaneDepthMode(
                            requestedDepthMode = requestedDepthMode,
                            automaticDepthSupported = automaticDepthSupported,
                            planeDepthOcclusion = planeDepthOcclusion,
                        ),
                    )
                }
            }
        }
    }
}
