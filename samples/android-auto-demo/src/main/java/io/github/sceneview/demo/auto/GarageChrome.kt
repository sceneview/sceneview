package io.github.sceneview.demo.auto

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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

/**
 * What is on screen: where the driver is ([eyebrow] — the garage or the road), the car's name
 * and its author (CC BY asks for it wherever the work is shown).
 */
@Composable
internal fun TitleBlock(eyebrow: String, car: Car, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(AutoTokens.Space.xs)) {
        Text(
            text = eyebrow.uppercase(),
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
 * The showroom's controls. Three of them are a single large target that steps to the next
 * choice — at arm's length in a car, one tap on a 76 dp bar beats a picker, a list or a slider —
 * and the fourth takes the car on show out on the floor.
 */
@Composable
internal fun ControlRow(
    selection: GarageSelection,
    onSelection: (GarageSelection) -> Unit,
    onDrive: () -> Unit,
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
        ActionControl(text = stringResource(R.string.garage_control_drive), onClick = onDrive)
    }
}

/**
 * The control that starts something: filled with the accent, the one place the row is not glass.
 * Also the way back from the road ([forward] `false`), in glass, alone in a corner.
 */
@Composable
internal fun ActionControl(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    forward: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) AutoTokens.PRESSED_SCALE else 1f,
        animationSpec = AutoTokens.spring,
        label = "press",
    )
    val fill = if (forward) AutoTokens.Accent.primary else AutoTokens.Glass.surface
    val ink = if (forward) AutoTokens.Accent.onPrimary else AutoTokens.Glass.onGlass
    Row(
        modifier = modifier
            .width(AutoTokens.Car.actionWidth)
            .height(AutoTokens.Car.touchTarget)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .overMediaEdge(AutoTokens.Radius.lg)
            .clip(RoundedCornerShape(AutoTokens.Radius.lg))
            .background(fill)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AutoTokens.Space.sm, Alignment.CenterHorizontally),
    ) {
        if (!forward) Chevron(pointsRight = false, color = ink, modifier = Modifier.size(AutoTokens.Space.md))
        Text(text = text, style = AutoTokens.Type.card, color = ink, maxLines = 1)
        if (forward) Chevron(pointsRight = true, color = ink, modifier = Modifier.size(AutoTokens.Space.md))
    }
}

/**
 * The road's controls, held rather than tapped: steering under the left thumb, the pedals under
 * the right. Each pad is its own touch target, so two thumbs steer and accelerate at once.
 */
@Composable
internal fun BoxScope.DrivePads(pad: DrivePad) {
    Row(
        modifier = Modifier.align(Alignment.BottomStart),
        horizontalArrangement = Arrangement.spacedBy(AutoTokens.Space.md),
    ) {
        HoldPad(stringResource(R.string.drive_steer_left), onHeld = { pad.left = it }) { ink ->
            Chevron(pointsRight = false, color = ink, modifier = Modifier.size(AutoTokens.Space.xl))
        }
        HoldPad(stringResource(R.string.drive_steer_right), onHeld = { pad.right = it }) { ink ->
            Chevron(pointsRight = true, color = ink, modifier = Modifier.size(AutoTokens.Space.xl))
        }
    }
    Row(
        modifier = Modifier.align(Alignment.BottomEnd),
        horizontalArrangement = Arrangement.spacedBy(AutoTokens.Space.md),
    ) {
        val brake = stringResource(R.string.drive_brake)
        HoldPad(brake, onHeld = { pad.brake = it }) { ink ->
            Text(text = brake.uppercase(), style = AutoTokens.Type.card, color = ink)
        }
        val go = stringResource(R.string.drive_go)
        HoldPad(go, onHeld = { pad.throttle = it }) { ink ->
            Text(text = go.uppercase(), style = AutoTokens.Type.card, color = ink)
        }
    }
}

/** What the driver's thumbs are on. Plain fields: the frame loop reads them, nothing recomposes. */
internal class DrivePad {
    var left = false
    var right = false
    var throttle = false
    var brake = false

    val input: DriveInput
        get() = DriveInput(
            throttle = throttle,
            brake = brake,
            steer = (if (right) 1 else 0) - (if (left) 1 else 0),
        )

    fun release() {
        left = false
        right = false
        throttle = false
        brake = false
    }
}

/**
 * One pad: [onHeld] is `true` from the finger landing until every finger on it has lifted — a
 * thumb that drifts off the pad keeps its hold, the way a pedal keeps its foot. Held, it fills
 * with the accent.
 */
@Composable
private fun HoldPad(
    description: String,
    onHeld: (Boolean) -> Unit,
    content: @Composable (ink: Color) -> Unit,
) {
    var held by remember { mutableStateOf(false) }
    val currentOnHeld by rememberUpdatedState(onHeld)
    val scale by animateFloatAsState(
        targetValue = if (held) AutoTokens.PRESSED_SCALE else 1f,
        animationSpec = AutoTokens.spring,
        label = "hold",
    )
    DisposableEffect(Unit) { onDispose { currentOnHeld(false) } }
    Box(
        modifier = Modifier
            .size(AutoTokens.Car.drivePad)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .overMediaEdge(AutoTokens.Radius.lg)
            .clip(RoundedCornerShape(AutoTokens.Radius.lg))
            .background(if (held) AutoTokens.Accent.primary else AutoTokens.Glass.surface)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    held = true
                    currentOnHeld(true)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    held = false
                    currentOnHeld(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content(if (held) AutoTokens.Accent.onPrimary else AutoTokens.Glass.onGlass)
    }
}

/** A chevron drawn from the tokens: no icon font, no vector asset, crisp at any chrome scale. */
@Composable
private fun Chevron(pointsRight: Boolean, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.drawBehind {
            val stroke = AutoTokens.Space.xs.toPx()
            val tip = if (pointsRight) size.width * CHEVRON_TIP else size.width * (1f - CHEVRON_TIP)
            val tail = size.width - tip
            val path = Path().apply {
                moveTo(tail, size.height * CHEVRON_INSET)
                lineTo(tip, size.height / 2f)
                lineTo(tail, size.height * (1f - CHEVRON_INSET))
            }
            drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    )
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
            .padding(horizontal = AutoTokens.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AutoTokens.Space.xs)) {
            // The pips share the label's line, so the value below has the control's full width.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label.uppercase(),
                    style = AutoTokens.Type.label,
                    color = AutoTokens.Glass.onGlassMuted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (enabled) Pips(index = index, count = count)
            }
            Text(
                text = value,
                style = AutoTokens.Type.card,
                color = if (enabled) AutoTokens.Glass.onGlass else AutoTokens.Glass.onGlassMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
 * The curtain between the showroom and the road: the stage's own dark, faded over everything
 * while the car and the camera change places, so neither is ever seen jumping.
 */
@Composable
internal fun StageCurtain(alpha: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .background(AutoTokens.Stage.background)
    )
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

/** Where a chevron's point sits across its box, and how far its arms stop from the edges. */
private const val CHEVRON_TIP = 0.68f
private const val CHEVRON_INSET = 0.2f
