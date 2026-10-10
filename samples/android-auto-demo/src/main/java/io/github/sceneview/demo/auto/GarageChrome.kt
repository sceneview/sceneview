package io.github.sceneview.demo.auto

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp

/** `chrome-scrim` bands: the ground the title and the controls are read on, over any car. */
@Composable
internal fun BoxScope.ChromeScrims() {
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .fillMaxHeight(SCRIM_FRACTION)
            .background(Brush.verticalGradient(listOf(AutoTokens.Glass.scrim, Color.Transparent)))
    )
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(SCRIM_FRACTION)
            .background(Brush.verticalGradient(listOf(Color.Transparent, AutoTokens.Glass.scrim)))
    )
}

/** What is on the podium: the garage's name, the car's, and its author (CC BY asks for it). */
@Composable
internal fun TitleBlock(car: Car, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(AutoTokens.Space.xs)) {
        Text(
            text = stringResource(R.string.garage_title).uppercase(),
            style = AutoTokens.Type.label,
            color = AutoTokens.Accent.primary,
        )
        Text(
            text = car.label,
            style = AutoTokens.Type.display,
            color = AutoTokens.Glass.onGlass,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = car.credit,
            style = AutoTokens.Type.caption,
            color = AutoTokens.Glass.onGlassMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The three controls. Each one is a single large target that steps to the next choice: at arm's
 * length in a car, one tap on a 76 dp bar beats a picker, a list or a slider.
 */
@Composable
internal fun ControlRow(
    selection: GarageSelection,
    onSelection: (GarageSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cars = GarageCatalog.cars
    val lightings = GarageCatalog.lightings
    val car = cars[selection.car]
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AutoTokens.Space.md, Alignment.CenterHorizontally),
    ) {
        GarageControl(
            label = stringResource(R.string.garage_control_car),
            value = car.label,
            index = selection.car,
            count = cars.size,
            onClick = { onSelection(selection.nextCar(cars)) },
            modifier = Modifier.weight(1f, fill = false),
        )
        GarageControl(
            label = stringResource(R.string.garage_control_paint),
            value = car.paints.getOrNull(selection.paint)?.label
                ?: stringResource(R.string.garage_paint_factory),
            index = selection.paint,
            count = car.paints.size,
            onClick = { onSelection(selection.nextPaint(cars)) },
            modifier = Modifier.weight(1f, fill = false),
        )
        GarageControl(
            label = stringResource(R.string.garage_control_lighting),
            value = lightings[selection.lighting].label,
            index = selection.lighting,
            count = lightings.size,
            onClick = { onSelection(selection.nextLighting(lightings)) },
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * One glass control: what it changes, the current choice, and one pip per choice. With fewer
 * than two choices there is nothing to step through, so it is shown but not tappable.
 */
@Composable
private fun GarageControl(
    label: String,
    value: String,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = count > 1
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) AutoTokens.PRESSED_SCALE else 1f,
        animationSpec = AutoTokens.spring,
        label = "press",
    )
    val shape = RoundedCornerShape(AutoTokens.Radius.lg)
    Row(
        modifier = modifier
            .widthIn(max = AutoTokens.Car.controlMaxWidth)
            .fillMaxWidth()
            .height(AutoTokens.Car.touchTarget)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .overMediaEdge(AutoTokens.Radius.lg)
            .clip(shape)
            .background(AutoTokens.Glass.surface)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClickLabel = stringResource(R.string.garage_action_next),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = AutoTokens.Space.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AutoTokens.Space.md),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AutoTokens.Space.xs)) {
            Text(
                text = label.uppercase(),
                style = AutoTokens.Type.label,
                color = AutoTokens.Glass.onGlassMuted,
                maxLines = 1,
            )
            Text(
                text = value,
                style = AutoTokens.Type.card,
                color = if (enabled) AutoTokens.Glass.onGlass else AutoTokens.Glass.onGlassMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (enabled) Pips(index = index, count = count)
    }
}

/** Where the current choice sits among the others. */
@Composable
private fun Pips(index: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(AutoTokens.Space.xs)) {
        repeat(count) { pip ->
            Box(
                Modifier
                    .size(AutoTokens.Car.pip)
                    .clip(CircleShape)
                    .background(if (pip == index) AutoTokens.Accent.primary else AutoTokens.Glass.edgeRing)
            )
        }
    }
}

/**
 * The cover the garage is revealed from. It stays up until every car has been drawn once, so
 * what the driver sees first is the finished scene — never an empty podium, never a model
 * appearing a beat after its neighbours.
 */
@Composable
internal fun LoadingCover(alpha: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .background(AutoTokens.Stage.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.garage_title),
                style = AutoTokens.Type.display,
                color = AutoTokens.Glass.onGlass,
            )
            Spacer(Modifier.height(AutoTokens.Space.sm))
            Text(
                text = stringResource(R.string.garage_opening),
                style = AutoTokens.Type.caption,
                color = AutoTokens.Glass.onGlassMuted,
            )
            Spacer(Modifier.height(AutoTokens.Space.lg))
            CircularProgressIndicator(
                modifier = Modifier.size(AutoTokens.Space.xl),
                color = AutoTokens.Accent.primary,
                trackColor = AutoTokens.Glass.surface,
            )
        }
    }
}

/**
 * `over-media-edge` (`DESIGN.md`): a white ring straddling the shape's boundary and a black halo
 * one band further out, both drawn outside the fill so the glass stays clean. One of the two
 * always contrasts with what is behind — a white car or a black floor.
 */
private fun Modifier.overMediaEdge(radius: Dp): Modifier = drawBehind {
    val band = AutoTokens.Glass.edgeWidth.toPx()
    val corner = radius.toPx()
    drawRoundRect(
        color = AutoTokens.Glass.edgeHalo,
        topLeft = Offset(-band, -band),
        size = Size(size.width + 2 * band, size.height + 2 * band),
        cornerRadius = CornerRadius(corner + band),
        style = Stroke(width = band),
    )
    drawRoundRect(
        color = AutoTokens.Glass.edgeRing,
        cornerRadius = CornerRadius(corner),
        style = Stroke(width = band),
    )
}

/** How far each scrim band reaches into the stage. */
private const val SCRIM_FRACTION = 0.3f
