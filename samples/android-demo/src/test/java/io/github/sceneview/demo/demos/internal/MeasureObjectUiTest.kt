package io.github.sceneview.demo.demos.internal

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Review and repeat on both themes. Editor input and provenance are covered by MeasureObjectTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeasureObjectUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `review identifies manual values and repeats only width in light theme`() = review(false)
    @Test fun `review identifies manual values and repeats only width in dark theme`() = review(true)

    private fun review(dark: Boolean) {
        compose.setContent {
            SceneViewDemoTheme(darkTheme = dark) {
                var measure by remember { mutableStateOf(ObjectMeasure<Int>(
                    stage = ObjectMeasureStage.Review,
                    dimensions = mapOf(
                        ObjectDimension.Length to ObjectMeasureDimension(value = ObjectMeasureValue(220.0, ObjectMeasureProvenance.Measured)),
                        ObjectDimension.Width to ObjectMeasureDimension(value = ObjectMeasureValue(53.5, ObjectMeasureProvenance.Manual)),
                        ObjectDimension.Height to ObjectMeasureDimension(value = ObjectMeasureValue(35.0, ObjectMeasureProvenance.Measured)),
                    ),
                )) }
                Column {
                    MeasureObjectCard(measure, true, null, { measure = it })
                    MeasureObjectButton(measure, { measure = it }, {})
                }
            }
        }
        compose.onNodeWithText("220 × 53.5 × 35 mm").assertIsDisplayed()
        compose.onNodeWithText(OBJECT_MEASURE_UNCERTAINTY).assertIsDisplayed()
        compose.onNodeWithText("Width: 53.5 mm · manual").assertIsDisplayed()
        compose.onAllNodesWithText("Repeat")[1].performScrollTo().performClick()
        compose.onNodeWithText("Capture width · 2 of 3").assertIsDisplayed()
        compose.onNodeWithText("L: 220").assertIsDisplayed()
        compose.onNodeWithText("H: 35").assertIsDisplayed()
        compose.onNodeWithText("Capture endpoint 1").assertIsDisplayed()
    }
}
