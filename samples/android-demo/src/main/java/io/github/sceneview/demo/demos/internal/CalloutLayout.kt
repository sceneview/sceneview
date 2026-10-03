package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the `2D in 3D` demo's Compose cards sit around the model, and which way they face.
 *
 * All of it is plain arithmetic on floats — no engine types, no Filament call — so the framing
 * is something `CalloutLayoutTest` can assert instead of a number tuned by eye. The demo
 * composable ([io.github.sceneview.demo.demos.TwoDInThreeDDemo]) only reads the results.
 *
 * ## The frame the numbers live in
 *
 * The model and every card are children of one **turntable** node that spins about Y. Inside
 * that node's local frame a card at [Callout.angleDegrees] `θ` and radius `r` sits at
 * `(r·sin θ, height, r·cos θ)` — `θ = 0` puts it between the model and the camera's home
 * position, and `θ` grows counter-clockwise seen from above, matching Filament's right-handed
 * Y-up convention.
 *
 * Parenting the cards to the turntable rather than to the world is what makes the Billboard
 * toggle legible: with billboarding off the cards ride round with the model and go edge-on;
 * with it on they pivot to stay square to the viewer while their *positions* still orbit.
 * Cards pinned in world space would look identical either way until the user orbited the
 * camera.
 */
internal object CalloutLayout {

    /** The model's largest dimension, in metres, after `scaleToUnits`. */
    const val MODEL_SIZE_METERS = 0.62f

    /** Camera orbit radius from [targetPosition], in metres. */
    const val CAMERA_DISTANCE = 2.6f

    /** Camera height above [targetPosition], in metres. A gentle look-down. */
    const val CAMERA_HEIGHT = 0.37f

    /**
     * Height of the point the camera looks at and orbits around, in metres.
     *
     * Below the model on purpose. The frame holds two things — the helmet at the origin and the
     * control card under it — so the camera aims between them, on the turntable's own axis: the
     * helmet lands in the upper half of the scene, the card in the lower half, and an orbit still
     * turns about the model rather than about a point in front of it.
     *
     * The scene disables `autoCenterContent`, so nothing re-centres the content behind these
     * numbers: `CalloutLayoutTest` projects both subjects through this camera and fails if either
     * leaves a portrait viewport.
     */
    const val TARGET_Y = -0.30f

    /** Turntable rotation, in degrees per second. One revolution every 24 s. */
    const val SPIN_DEGREES_PER_SECOND = 15f

    /**
     * Turntable angle frozen under `DemoSettings.qaMode`, so captures are deterministic.
     *
     * Zero, and deliberately so: [CALLOUTS]' own bearings already carry the composition, and at
     * turntable zero the Vents card sits half behind the model — which is the frame that makes
     * the **Always on top** toggle show a difference at all.
     */
    const val QA_SPIN_DEGREES = 0f

    // ── Card spread (how far the cards stand off the model) ───────────────────────────────

    /**
     * Closest the cards may stand to the turntable axis, in metres.
     *
     * Deliberately *inside* the model's own silhouette at the low end. A call-out that
     * overlaps the thing it names is what a real annotation UI looks like — and it is the only
     * arrangement in which **Always on top** has anything to show.
     */
    const val MIN_SPREAD = 0.28f

    /** Furthest the cards may stand from the turntable axis, in metres. */
    const val MAX_SPREAD = 0.70f

    /** Default card radius — just clear of the model's silhouette. */
    const val DEFAULT_SPREAD = 0.34f

    // ── Card size (world scale applied to the rendered Compose quad) ──────────────────────

    /**
     * Card scale bounds. At [CARD_DP_PER_METER] the demo's 264 dp card is a couple of metres
     * across at scale 1 — these are the factors that bring it back to a hand-sized label next to
     * a 0.62 m model.
     */
    const val MIN_CARD_SCALE = 0.07f

    /** @see MIN_CARD_SCALE */
    const val MAX_CARD_SCALE = 0.18f

    /** @see MIN_CARD_SCALE */
    const val DEFAULT_CARD_SCALE = 0.12f

    /** Width of every in-scene card, in dp. One box for all of them — see `CardShell`. */
    const val CARD_WIDTH_DP = 264f

    /** Height of every in-scene card, in dp. */
    const val CARD_HEIGHT_DP = 156f

    /**
     * The display density the card rate is anchored on: 420 dpi, the profile the demo's render
     * goldens are recorded at. Only [CARD_DP_PER_METER] reads it.
     */
    const val FRAMING_DENSITY = 2.625f

    /**
     * How many dp of card make one metre of world, at node scale 1.
     *
     * A `ViewNode` is sized in **pixels**: its quad is the view's pixel size over
     * `ViewNode.pxPerUnits`, which defaults to a fixed 250 px/m. Left at that default the same
     * 264 dp card is 2.1 m wide on an xhdpi display and 3.7 m wide on a 560 dpi one, and the
     * scene around it does not grow with it: on a dense phone the control card overflowed the
     * viewport. So the demo sets `pxPerUnits` from the display density ([viewNodePxPerUnit]),
     * which makes the rate a constant in dp and the card the same size in the world on every
     * display. The value is the default rate at [FRAMING_DENSITY]: at 420 dpi a card is the
     * size it always was, and every other display now matches it.
     */
    const val CARD_DP_PER_METER = 250f / FRAMING_DENSITY

    /**
     * How much larger than a call-out the control card is drawn. It carries a button, so it has
     * to stay a comfortable touch target at the distance the call-outs are merely readable from.
     */
    const val CONTROL_CARD_SCALE_FACTOR = 2.5f

    /**
     * The `ViewNode.pxPerUnits` every card of this demo is built with on a display of the given
     * [density]: [CARD_DP_PER_METER] expressed in that display's pixels.
     */
    fun viewNodePxPerUnit(density: Float): Float = CARD_DP_PER_METER * density

    /**
     * A card's world width, in metres: its dp width over [CARD_DP_PER_METER], times the node
     * scale. The same on every display, as long as the node uses [viewNodePxPerUnit].
     */
    fun cardWorldWidth(cardScale: Float): Float = CARD_WIDTH_DP / CARD_DP_PER_METER * cardScale

    /** A card's world height, in metres. @see cardWorldWidth */
    fun cardWorldHeight(cardScale: Float): Float = CARD_HEIGHT_DP / CARD_DP_PER_METER * cardScale

    /**
     * The three annotations pinned to the model, in the order they are composed.
     *
     * The copy is about the *asset*, not about the demo: a call-out that says something true
     * about the thing it points at is what makes the pattern worth copying. Angles are spread
     * so no two cards overlap at [DEFAULT_SPREAD], and heights stagger them vertically so the
     * turntable never lines all three up into one stripe.
     */
    val CALLOUTS: List<Callout> = listOf(
        Callout(
            id = "visor",
            title = "Visor",
            body = "Base color and metallic-roughness, 2048² each.",
            angleDegrees = -58f,
            height = 0.27f,
        ),
        Callout(
            id = "vents",
            title = "Vents",
            body = "An emissive map. They glow with no light aimed at them.",
            angleDegrees = 128f,
            height = 0f,
        ),
        Callout(
            id = "shell",
            title = "Shell",
            body = "Every dent is a normal map. The mesh under it is smooth.",
            angleDegrees = 34f,
            height = -0.26f,
        ),
    )

    /**
     * The interactive card's world position — in front of the model and below it, **not** on the
     * turntable.
     *
     * A label may turn away; a control may not. Parenting the control card to the turntable would
     * carry the only tappable thing in the scene round the back every twelve seconds, so it is
     * pinned in world space, billboarded on every frame, and always drawn on top. That split —
     * world-anchored annotations, viewer-anchored controls — is the part of this demo most worth
     * copying into a real app.
     *
     * These are the coordinates the card is rendered at, not a starting point the scene then
     * shifts: `autoCenterContent` is off. It stands just clear of the helmet's front face, and
     * low enough for its top edge to sit under the chin — any closer to the camera and a card
     * [CONTROL_CARD_SCALE_FACTOR] times a call-out is wider than a portrait viewport.
     */
    val CONTROL_CARD_POSITION = Position(x = 0f, y = -0.58f, z = 0.5f)

    /**
     * The camera's home position, [CAMERA_DISTANCE] away from [targetPosition].
     *
     * `rememberCameraManipulator` reads the **length** of `orbitHomePosition` as the orbit
     * distance (see `GeometryLayout` and #2930), so the vector has to be the real eye offset,
     * not a direction.
     *
     * @param distance Overrides [CAMERA_DISTANCE] — how `DemoSettings.cameraDistance` (#2652)
     * reaches this scene, so a capture run can reframe it from adb.
     */
    fun cameraHomePosition(distance: Float = CAMERA_DISTANCE): Position {
        val safeDistance = distance.coerceAtLeast(MIN_CAMERA_DISTANCE)
        // Keep the look-down angle constant as the distance changes: the height scales with
        // the radius instead of staying pinned, so pulling back does not flatten the view.
        val height = CAMERA_HEIGHT * (safeDistance / CAMERA_DISTANCE)
        val horizontal = kotlin.math.sqrt(
            (safeDistance * safeDistance - height * height).coerceAtLeast(0f)
        )
        return Position(x = 0f, y = TARGET_Y + height, z = horizontal)
    }

    /** The point the camera looks at and orbits around — on the turntable's axis, at [TARGET_Y]. */
    fun targetPosition(): Position = Position(x = 0f, y = TARGET_Y, z = 0f)

    /**
     * A card's position **in the turntable's local frame**.
     *
     * @param spread Radius from the turntable axis, in metres. Clamped to
     * [MIN_SPREAD]…[MAX_SPREAD].
     */
    fun localPosition(callout: Callout, spread: Float): Position {
        val radius = spread.coerceIn(MIN_SPREAD, MAX_SPREAD)
        val radians = Math.toRadians(callout.angleDegrees.toDouble())
        return Position(
            x = (radius * sin(radians)).toFloat(),
            y = callout.height,
            z = (radius * cos(radians)).toFloat(),
        )
    }

    /**
     * The card's **world** position once the turntable has spun by [turntableYawDegrees].
     *
     * Rotating `(x, z)` about Y by `yaw` in a right-handed Y-up frame gives
     * `x' = x·cos yaw + z·sin yaw`, `z' = −x·sin yaw + z·cos yaw`; because the local position
     * is itself `(r·sin θ, h, r·cos θ)`, that collapses to the same expression at `θ + yaw`.
     * Kept as the explicit rotation anyway, so the function stays correct if the local
     * placement ever stops being a circle.
     */
    fun worldPosition(callout: Callout, spread: Float, turntableYawDegrees: Float): Position {
        // The turntable sits at the world origin and the scene disables `autoCenterContent`, so
        // its yaw is the whole transform between a card's local position and its world one.
        val local = localPosition(callout, spread)
        val yaw = Math.toRadians(turntableYawDegrees.toDouble())
        val cosYaw = cos(yaw).toFloat()
        val sinYaw = sin(yaw).toFloat()
        return Position(
            x = local.x * cosYaw + local.z * sinYaw,
            y = local.y,
            z = -local.x * sinYaw + local.z * cosYaw,
        )
    }

    /**
     * Yaw, in degrees, that turns a quad's front face (its local +Z) toward the camera.
     *
     * This is the billboard, done as arithmetic rather than as a per-frame `lookTowards` on the
     * node: the result is a plain `Float` the composable hands to `ViewNode(rotation = …)`, so
     * the card's orientation stays declarative and this file stays testable. Only the yaw is
     * solved — a card that also pitched toward a camera looking slightly down would lean
     * backwards, and a wall of leaning labels reads as a bug, not as a feature.
     *
     * @param cardWorldPosition Where the quad actually is, in world space.
     * @param cameraWorldPosition Live camera position. The user can orbit, so this is read every
     * frame rather than assumed to be [cameraHomePosition].
     * @param parentYawDegrees Yaw of the node the result will be applied *under*. The returned
     * angle is a **local** rotation, so the parent's own turn has to be subtracted out; pass `0`
     * for a quad parented to the world.
     * @return An angle in `(-180, 180]`, or `0` when the camera is degenerately close to the
     * quad — `atan2(0, 0)` is not meaningful, and a card the eye sits inside cannot be faced.
     */
    fun billboardYawDegrees(
        cardWorldPosition: Position,
        cameraWorldPosition: Position,
        parentYawDegrees: Float = 0f,
    ): Float {
        val dx = cameraWorldPosition.x - cardWorldPosition.x
        val dz = cameraWorldPosition.z - cardWorldPosition.z
        if (dx * dx + dz * dz < DEGENERATE_DISTANCE_SQ) return 0f
        // atan2(x, z) — not the usual atan2(y, x). A heading measured from +Z toward +X is
        // exactly the convention `localPosition` places the cards with, so a card seen from its
        // own radial direction comes back with its own bearing.
        val headingDegrees = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
        return normalizeDegrees(headingDegrees - parentYawDegrees)
    }

    /** [billboardYawDegrees] for a call-out riding the turntable. */
    fun billboardYawDegrees(
        callout: Callout,
        spread: Float,
        turntableYawDegrees: Float,
        cameraWorldPosition: Position,
    ): Float = billboardYawDegrees(
        cardWorldPosition = worldPosition(callout, spread, turntableYawDegrees),
        cameraWorldPosition = cameraWorldPosition,
        parentYawDegrees = turntableYawDegrees,
    )

    /**
     * Yaw, in the turntable's local frame, of a card that is **not** billboarded: it faces
     * radially outward, away from the model, and rides round with the turntable.
     */
    fun fixedYawDegrees(callout: Callout): Float = normalizeDegrees(callout.angleDegrees)

    /** Wraps an angle into `(-180, 180]`, so a spin that has run for minutes stays readable. */
    fun normalizeDegrees(degrees: Float): Float {
        var value = degrees % 360f
        if (value <= -180f) value += 360f
        if (value > 180f) value -= 360f
        // `-0f` compares equal to `0f` but prints as "-0.0"; normalise it away.
        return if (value == 0f) 0f else value
    }

    /**
     * Advances the turntable angle by one frame, wrapped into `[0, 360)`.
     *
     * @param deltaNanos Time since the previous frame, straight from `withFrameNanos`.
     */
    fun nextTurntableYaw(previousDegrees: Float, deltaNanos: Long): Float {
        if (deltaNanos <= 0L) return previousDegrees
        val seconds = deltaNanos / NANOS_PER_SECOND
        val advanced = previousDegrees + SPIN_DEGREES_PER_SECOND * seconds
        return ((advanced % 360f) + 360f) % 360f
    }

    /** Below this squared distance (1 cm²) the camera is treated as sitting on the card. */
    private const val DEGENERATE_DISTANCE_SQ = 1e-4f

    /** Guards `cameraHomePosition` against a `?camera_distance=0` extra flattening the rig. */
    private const val MIN_CAMERA_DISTANCE = 0.6f

    private const val NANOS_PER_SECOND = 1_000_000_000f
}

/**
 * One annotation pinned around the model.
 *
 * @param id Stable key for `key(…)` in the composition, and the UI-test handle.
 * @param title Card headline.
 * @param body One sentence about the part the card points at. Empty for the control card,
 * which carries a live control instead of prose.
 * @param angleDegrees Bearing around the turntable axis, `0` facing the camera's home.
 * @param height Metres above (positive) or below (negative) the model's centre.
 */
internal data class Callout(
    val id: String,
    val title: String,
    val body: String,
    val angleDegrees: Float,
    val height: Float,
)
