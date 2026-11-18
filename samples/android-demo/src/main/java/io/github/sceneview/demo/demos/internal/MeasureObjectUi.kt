package io.github.sceneview.demo.demos.internal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode

internal const val OBJECT_MEASURE_UNCERTAINTY = "Approximate — accuracy not calibrated"

internal fun objectMeasureNumber(value: ObjectMeasureValue?): String = value?.let {
    BigDecimal.valueOf(it.millimeters).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
} ?: "—"

internal fun objectMeasureChip(axis: ObjectDimension, value: ObjectMeasureValue?): String =
    "${axis.symbol}: ${objectMeasureNumber(value)}" + if (value != null) " mm" else ""

internal fun objectMeasureStatus(axis: ObjectDimension, value: ObjectMeasureValue): String =
    "${axis.name} " + when (value.provenance) {
        ObjectMeasureProvenance.Manual -> "entered manually"
        ObjectMeasureProvenance.Measured -> "captured"
    } + ". Continue, correct or repeat."

internal fun objectMeasureInputError(input: String, edited: Boolean): Boolean =
    edited && parseObjectMillimeters(input) == null

@Composable
internal fun <P> MeasureObjectCard(
    measure: ObjectMeasure<P>,
    depthSupported: Boolean,
    message: String?,
    onChange: (ObjectMeasure<P>) -> Unit,
) {
    var editing by remember { mutableStateOf<ObjectDimension?>(null) }
    val maxCardHeight = if (measure.stage == ObjectMeasureStage.Review) 280.dp else
        (LocalConfiguration.current.screenHeightDp / 2 - 120).coerceIn(96, 280).dp
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxCardHeight).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                when (measure.stage) {
                    ObjectMeasureStage.Prepare -> "Prepare object measurement"
                    ObjectMeasureStage.Capture -> "Capture ${measure.selected.name.lowercase()} · ${measure.selected.ordinal + 1} of 3"
                    ObjectMeasureStage.Review -> "Review · L × W × H"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(OBJECT_MEASURE_UNCERTAINTY, style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            when (measure.stage) {
                ObjectMeasureStage.Prepare -> {
                    Text("Hold the phone 50–60 cm from the object and move slightly sideways. Keep the object still.")
                    Text("Aim at two ends of each dimension on the object. Change your viewpoint for height; avoid the table and cables.", style = MaterialTheme.typography.bodySmall)
                    if (!depthSupported) Text("Depth is not available yet. You can enter dimensions manually.", style = MaterialTheme.typography.bodySmall)
                }
                ObjectMeasureStage.Capture -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ObjectDimension.entries.forEach { axis ->
                            TextButton(onClick = { onChange(measure.select(axis)) }, modifier = Modifier.weight(1f)) {
                                Text(objectMeasureChip(axis, measure.dimension(axis).value))
                            }
                        }
                    }
                    val dimension = measure.dimension()
                    Text(
                        if (dimension.value != null) objectMeasureStatus(measure.selected, dimension.value)
                        else "Aim at endpoint ${dimension.endpoints.size + 1} of 2 on the object, then tap Capture.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row {
                        TextButton(onClick = { onChange(measure.undo()) }, enabled = dimension.endpoints.isNotEmpty() || dimension.value != null) { Text("Undo") }
                        TextButton(onClick = { onChange(measure.repeat(measure.selected)) }) { Text("Repeat") }
                        TextButton(onClick = { editing = measure.selected }) { Text("Edit mm") }
                    }
                }
                ObjectMeasureStage.Review -> {
                    Text(
                        ObjectDimension.entries.joinToString(" × ") { objectMeasureNumber(measure.dimension(it).value) } + " mm",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    ObjectDimension.entries.forEach { axis ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val value = measure.dimension(axis).value
                            TextButton(onClick = { editing = axis }, modifier = Modifier.weight(1f)) {
                                Text("${axis.name}: ${objectMeasureNumber(value)} mm" + if (value?.provenance == ObjectMeasureProvenance.Manual) " · manual" else "", color = MaterialTheme.colorScheme.onSurface)
                            }
                            TextButton(onClick = { onChange(measure.repeat(axis)) }) { Text("Repeat") }
                        }
                    }
                }
            }
        }
    }
    editing?.let { axis ->
        var input by remember(axis) { mutableStateOf(measure.dimension(axis).value?.let { objectMeasureNumber(it) }.orEmpty()) }
        var edited by remember(axis) { mutableStateOf(false) }
        val parsed = parseObjectMillimeters(input)
        val inputError = objectMeasureInputError(input, edited)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("${axis.name} in millimeters") },
            text = {
                OutlinedTextField(
                    value = input, onValueChange = { input = it; edited = true }, singleLine = true,
                    label = { Text("Millimeters") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = inputError,
                    supportingText = { Text(if (inputError) "Enter a number above 0." else "Saved as manual.") },
                )
            },
            confirmButton = { TextButton(enabled = parsed != null, onClick = {
                parsed?.let { onChange(measure.correct(axis, it)) }
                editing = null
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun <P> MeasureObjectButton(measure: ObjectMeasure<P>, onChange: (ObjectMeasure<P>) -> Unit, onCapture: () -> Unit) {
    Button(onClick = {
        when (measure.stage) {
            ObjectMeasureStage.Prepare -> onChange(measure.start())
            ObjectMeasureStage.Capture -> if (measure.dimension().value != null) onChange(measure.next()) else onCapture()
            ObjectMeasureStage.Review -> onChange(ObjectMeasure())
        }
    }, modifier = Modifier.padding(top = 12.dp)) {
        Text(when (measure.stage) {
            ObjectMeasureStage.Prepare -> "Start capture"
            ObjectMeasureStage.Capture -> if (measure.dimension().value != null) {
                if (measure.complete) "Review dimensions" else "Next dimension"
            } else "Capture endpoint ${measure.dimension().endpoints.size + 1}"
            ObjectMeasureStage.Review -> "New object"
        })
    }
}
