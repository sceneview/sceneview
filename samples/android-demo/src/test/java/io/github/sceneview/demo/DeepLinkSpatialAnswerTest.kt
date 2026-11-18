package io.github.sceneview.demo

import android.net.Uri
import io.github.sceneview.demo.demos.internal.spatial.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeepLinkSpatialAnswerTest {
    private val desk = SpatialAnswerSpec(1, SpatialObject.Box(listOf(0.8f, 0.75f, 0.4f)),
        DimensionSource.USER, SpatialPlacement.FLOOR, "Desk")

    @Test fun bothLinkSchemesCarryAnAnswer() {
        for (link in listOf(SpatialLink.customUri(desk), SpatialLink.httpsUri(desk))) {
            val uri = Uri.parse(link)
            assertEquals("spatial-answer", DeepLinkRouter.parse(uri))
            assertEquals(SpatialResult.Success(desk), SpatialLink.decode(DeepLinkRouter.parseAnswer(uri)!!))
        }
    }
    @Test fun invalidAnswersReachTheErrorState() {
        for (payload in listOf("", "malformed!", "a".repeat(2049))) {
            assertEquals(payload, DeepLinkRouter.parseAnswer(Uri.parse("sceneview://demo/spatial-answer?answer=${Uri.encode(payload)}")))
        }
        assertNull(DeepLinkRouter.parseAnswer(Uri.parse("sceneview://demo/spatial-answer")))
    }
    @Test fun answerIsRestrictedToSpatialAnswerAndTrustedRoutes() {
        for (link in listOf(
            "sceneview://demo/model-viewer?answer=test",
            "https://sceneview.github.io/open?demo=model-viewer&answer=test",
            "sceneview://evil/spatial-answer?answer=test",
            "https://evil.example/open?demo=spatial-answer&answer=test",
            "https://sceneview.github.io/other?demo=spatial-answer&answer=test",
            "ftp://sceneview.github.io/open?demo=spatial-answer&answer=test",
        )) assertNull(link, DeepLinkRouter.parseAnswer(Uri.parse(link)))
        assertNull(DeepLinkRouter.parseAnswer(null))
    }
}
