package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.theme.SceneViewTokens
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.camera.CameraShot
import io.github.sceneview.camera.CameraShotSink
import io.github.sceneview.camera.CameraShotVelocity
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.gesture.zoomedDistanceForPinch
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.math.toTransform
import io.github.sceneview.verticalFovDegreesForFocalLength
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The third-person camera of the 3D Scan demo's in-app 3D view (#3950).
 *
 * Two modes, and the switch between them is the whole design:
 *
 * - **Following** (the default, and what a double-tap returns to): the camera frames the session
 *   by itself — it eases towards [home], which the view recomputes as the trail and the planes
 *   grow, and sways gently about the side the room was scanned from. A phone held in one hand,
 *   walking a room, gets a view that keeps up without a finger on it.
 * - **Free**: the first touch takes over. Drag orbits (with inertia), two fingers pan, pinch
 *   zooms — all inside [limits], so no gesture can lose the room: the eye stays outside what was
 *   scanned and above its floor, and the pivot stays inside it. Nothing moves the camera but the
 *   user until a double-tap hands it back.
 *
 * Same frame contract as [StudioCameraManipulator]: [update] integrates, [getTransform] converts.
 */
@Suppress("TooManyFunctions") // one camera, its gestures and its framing, read in one place
class ArDebugOrbitCamera(
    initialPose: OrbitPose = ArDebugFraming.DEFAULT_POSE,
    /** Slow sway while following. Off in QA, so captures are deterministic. */
    var drift: Boolean = true,
    /** The clear part of the view the session is framed in, and the [lift] it draws at: see [OrbitBand]. */
    band: OrbitBand = OrbitBand.CARD,
    /** How fast following eases onto [home], per second ([ArDebugFraming.APPROACH_RATE]). */
    val approachRate: Float = ArDebugFraming.APPROACH_RATE,
    /** The fastest following swings its heading round, in degrees per second. */
    val maxTurnDegreesPerSecond: Float = ArDebugFraming.MAX_TURN_DEGREES_PER_SECOND,
    /**
     * The orbit a drag across the view's whole width makes, in degrees; `null` keeps the fixed
     * [DEGREES_PER_PIXEL]. 3D Scan's live view gives 180 (#4628): a third of the screen turns it
     * by 60°, the same on every phone, where 0.28°/px turned a Pixel 7a round by 500 px.
     */
    val degreesPerViewWidth: Float? = null,
    /** The elevations a drag can reach, inside [ArDebugFraming.MIN_ELEVATION]…[ArDebugFraming.MAX_ELEVATION]. */
    val elevationRange: ClosedFloatingPointRange<Float> =
        ArDebugFraming.MIN_ELEVATION..ArDebugFraming.MAX_ELEVATION,
    /**
     * The furthest a released drag coasts on, in degrees, `null` for the coast [MAX_SPIN] allows.
     * The fling is kept everywhere; 3D Scan bounds it so a flick cannot spin its cloud away.
     */
    val maxCoastDegrees: Float? = null,
) : CameraGestureDetector.CameraManipulator, CameraShotSink {

    /**
     * Where a touch that takes the camera over should orbit around, `null` for the pose's own
     * target. 3D Scan follows the phone, but its cloud lies ahead of it: a drag that turned around
     * the phone swung the cloud behind the camera (#4628), so the view sets this to the cloud's
     * centre and [takeOver] moves the orbit's pivot onto it, the picture unchanged.
     */
    var pivot: Position? = null

    /**
     * Told when [following] changes: a touch takes the camera over, a recenter hands it back.
     * The 3D Scan live view shows its Recenter pill off it (#4628).
     */
    var onFollowingChange: ((Boolean) -> Unit)? = null

    /** Current pose. Plain field, not Compose state: it changes every frame. */
    var pose: OrbitPose = initialPose
        private set

    /** Where following mode is heading; set by the view from the content bounds. */
    var home: OrbitPose = initialPose

    /**
     * The clear part of the view: a screen that turns to landscape, or whose chrome changes, hands
     * the camera its new band and the framing follows.
     */
    var band: OrbitBand = band

    /**
     * How far above the view's centre the pivot is drawn, as a fraction of the view's height: the
     * middle of the [band]. A full-screen stage with a HUD on top and a filmstrip below has its
     * clear band above the middle — lifting the picture there keeps the room out from under the
     * filmstrip.
     */
    val lift: Float get() = band.lift

    /**
     * The smaller band something covering part of [band] leaves the picture — the settings sheet
     * risen over the stage (#4564) — or `null` when nothing does. The framing is still fitted for
     * [band]: the picture is only drawn smaller and higher, into this band, so taking it away
     * brings back exactly the picture that was there before. The move is eased ([shownLift],
     * [shownScale]) and follows the sheet both ways.
     */
    var squeeze: OrbitBand? = null

    /**
     * The band the picture is drawn into while the demo is in full screen (#4558) — the whole
     * view, the chrome gone — or `null` with the chrome shown. Like [squeeze], the framing is
     * still fitted for [band]: the picture is drawn larger ([focusScale]) and at this band's lift,
     * eased on `motion-focus` both ways, so leaving full screen brings back the exact picture.
     */
    var focusBand: OrbitBand? = null

    /**
     * How much larger the picture is drawn in [focusBand] than in [band]: the ratio of the home
     * distances fitted for each, set by the view as it frames the session.
     */
    var focusScale: Float = 1f

    /** How far into full screen the picture is, 0..1, on the `motion-focus` timeline. */
    private var focusProgress: Float = 0f

    /** The last [focusBand], kept while the picture eases back out of it. */
    private var lastFocusBand: OrbitBand? = null

    /** The lift the picture is drawn at now, easing towards [squeeze]'s or [band]'s. */
    var shownLift: Float = band.lift
        private set

    /** The scale the picture is drawn at now (1 = fitted for [band]), easing towards [squeeze]'s. */
    var shownScale: Float = 1f
        private set

    /** What the gestures may do around the scene on screen; set by the view with [home]. */
    var limits: OrbitLimits = OrbitLimits.NONE

    /**
     * The side the session is best seen from — where the person who scanned it stood (see
     * [ArDebugFraming.frontAzimuth]). Following mode sways about it and Recenter returns to it, so
     * the camera never idles behind a wall's back.
     */
    var frontAzimuth: Float = initialPose.azimuthDegrees

    /**
     * The heading of the room's walls about +Y, in degrees, when its planes name one (see
     * [ArDebugGeometry.roomYawDegrees]): the map squares the floor plan with the screen.
     */
    var roomYawDegrees: Float? = null

    /** `true` while the camera frames the session by itself. */
    var following: Boolean = true
        private set(value) {
            if (field == value) return
            field = value
            onFollowingChange?.invoke(value)
        }

    /**
     * The phone camera's own eye (#4716): while following, and while it holds a tracked frame, the
     * camera stands exactly there instead of on the orbit, so what is drawn sits on the camera
     * image under the view. A drag or a pinch leaves it for the orbit, Recenter comes back to it.
     * The orbit keeps following the phone underneath, so the view a drag opens is the current one.
     */
    var firstPerson: ScanLens? = null

    /** `true` while the camera stands at [firstPerson]'s eye rather than on the orbit. */
    val inFirstPerson: Boolean get() = following && firstPerson?.ready == true

    /**
     * The eye the view is easing away from (#4719), while it does: [firstPerson] stopped seeing
     * through the phone — the save starting, tracking lost — while the view still followed.
     */
    private var handoff: EyeHandoff? = null

    /** Whether the last look at [firstPerson] found the view standing at its eye. */
    private var wasFirstPerson = false

    /**
     * `true` while the view stands at [firstPerson]'s eye or eases off its last one: what the
     * camera shows is the phone's own view, or still leaves it.
     */
    val seesThroughEye: Boolean get() = checkHandoff() != null || inFirstPerson

    /**
     * Starts an [EyeHandoff] the first time [firstPerson] is found no longer [ScanLens.ready]
     * while still following, and drops it once done or once a gesture takes the view over.
     */
    private fun checkHandoff(): EyeHandoff? {
        val lens = firstPerson
        if (lens != null && following && lens.ready) {
            wasFirstPerson = true
            handoff = null
            return null
        }
        if (wasFirstPerson) {
            wasFirstPerson = false
            if (lens != null && following && lens.held) {
                handoff = EyeHandoff(lens.cameraToWorld.copyOf(), lens.projection.copyOf())
            }
        }
        if (!following || handoff?.done == true) handoff = null
        return handoff
    }

    /**
     * The projection the view draws through, into [out], when it is not the orbit's own lens:
     * [firstPerson]'s while it stands at the phone's eye, the [EyeHandoff]'s mix while it eases
     * off it. `false` when the orbit's lens applies.
     */
    fun customProjection(out: FloatArray): Boolean {
        checkHandoff()?.let {
            it.projection(verticalFovDegrees, aspect, out)
            return true
        }
        val lens = firstPerson?.takeIf { inFirstPerson } ?: return false
        lens.projection.copyInto(out)
        return true
    }

    /** Viewport height in pixels, for pan speed and for the view's screen-constant sizes. */
    var viewportHeight: Int = 1
        private set

    /** Viewport width in pixels; with [viewportHeight], the aspect [ArDebugFraming.home] fits. */
    var viewportWidth: Int = 1
        private set

    val aspect: Float get() = viewportWidth.toFloat() / viewportHeight

    /** Set by the view once it has snapped to the first content, so it snaps only once. */
    var hasFramedContent: Boolean = false

    val verticalFovDegrees: Double = verticalFovDegreesForFocalLength(FOCAL_LENGTH_MM)

    private var lastX = 0
    private var lastY = 0
    private var panning = false
    private var grabbing = false
    private var dragAzimuth = 0f
    private var dragElevation = 0f
    private var azimuthVelocity = 0f
    private var elevationVelocity = 0f

    /** Seconds of following, which runs the sway from its still point. */
    private var followSeconds = 0f

    /** The heading following mode holds: [frontAzimuth], or the map's squared one. */
    private var heading = initialPose.azimuthDegrees

    /** Where the entrance crane ([playIntro]) started, `null` when none is playing. */
    private var introFrom: OrbitPose? = null
    private var introSeconds = 0f

    /**
     * Holds the crane on its first pose until [releaseIntro]: a scene still hidden behind its
     * loading cover (the GPU warming its shaders) must not spend the entrance where nobody sees it.
     */
    private var introHeld = false

    /** `true` while the entrance crane plays. */
    val introPlaying: Boolean get() = introFrom != null

    /**
     * The map framing: straight down on the room at [ArDebugFraming.MAP_ELEVATION], no sway, the
     * walls squared with the screen, so the floor plan reads like a plan. Switching hands the
     * camera back to the automatic framing.
     */
    var overhead: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            recenter()
        }

    /**
     * Keeps the turntable still while something in the view is being read — a picked object's
     * box and its dimensions: a recenter still reframes, it does not start the room turning again.
     */
    var steady: Boolean = false

    /** The elevation the view frames [home] at: the three-quarter view, or the map's. */
    val homeElevation: Float
        get() = if (overhead) ArDebugFraming.MAP_ELEVATION else ArDebugFraming.HOME_ELEVATION

    /**
     * Plays the entrance: the camera jumps to [from] and cranes onto [home] over
     * [ReplayIntro.DURATION_S] — following [home] as it goes, so a view that reframes mid-flight
     * still lands. The first touch cuts it short, like any following. With [held], it waits on its
     * first pose until [releaseIntro].
     */
    fun playIntro(from: OrbitPose, held: Boolean = false) {
        introHeld = held
        following = true
        azimuthVelocity = 0f
        elevationVelocity = 0f
        followSeconds = 0f
        introSeconds = 0f
        heading = home.azimuthDegrees
        introFrom = ArDebugFraming.clamp(from).also { pose = it }
    }

    /** Starts a crane [playIntro] held: the scene is on screen now. */
    fun releaseIntro() {
        introHeld = false
    }

    /**
     * Hands the camera back to the automatic framing — the double-tap and the Recenter button. It
     * returns to the room's good side by the shortest way round: wherever a finger left the
     * camera, one tap brings the room back.
     */
    fun recenter() {
        glide = null
        following = true
        followSeconds = 0f
        azimuthVelocity = 0f
        elevationVelocity = 0f
        heading = if (overhead) {
            ArDebugFraming.mapAzimuth(pose.azimuthDegrees, roomYawDegrees)
        } else {
            pose.azimuthDegrees + CameraRig.shortestDelta(pose.azimuthDegrees, frontAzimuth)
        }
        home = home.copy(azimuthDegrees = heading)
    }

    /**
     * Following mode heads for [target], its heading included: 3D Scan's live view hands it the
     * phone-follow pose every frame ([ScanFollow]), where the other views set [home] and keep the
     * room's own heading.
     */
    fun followTo(target: OrbitPose) {
        heading = target.azimuthDegrees
        home = target
    }

    /**
     * Stops the automatic framing where it is, as a drag does: a finger put on the view to tap
     * something in it must find that thing still there when the tap is told from a double-tap.
     */
    fun hold() {
        glide = null
        if (following) takeOver()
    }

    /**
     * A finger put down on the view: [hold], unless it is the second tap of the double-tap that
     * has just asked for a recenter — the double-tap is told before its own finger down.
     */
    fun fingerDown() {
        if (!recenteredByTap) hold()
    }

    private var recenteredByTap = false

    /** Where the free camera is being slid to by [glideTo], `null` once there or once touched. */
    private var glide: OrbitPose? = null

    /**
     * Where the camera is once [glideTo]'s slide is over — [pose] itself with none under way. What
     * is written over the view is placed for this pose, so it is there from the slide's first frame.
     */
    val restingPose: OrbitPose get() = glide ?: pose

    /**
     * `true` while a finger moves the camera — an orbit, a pan, a pinch — and while the spin it
     * let go of dies down: nothing brings the picture back under a finger still at work on it.
     */
    val moving: Boolean
        get() = grabbing || pinching || azimuthVelocity != 0f || elevationVelocity != 0f

    private var pinching = false

    /**
     * Slides the free camera to [target] — a picked object brought out from under the chrome. The
     * next touch stops it where it is. While following, the view frames through [home] instead.
     */
    fun glideTo(target: OrbitPose) {
        if (following) return
        azimuthVelocity = 0f
        elevationVelocity = 0f
        glide = ArDebugFraming.clamp(target).takeIf { it != pose }
    }

    /** Puts the camera at [target] at once — the first frame of a view, where easing would lag. */
    fun snapTo(target: OrbitPose) {
        pose = ArDebugFraming.clamp(target)
        heading = pose.azimuthDegrees
        focusProgress = if (focusBand != null) 1f else 0f
        lastFocusBand = focusBand ?: lastFocusBand
        shownLift = targetLift()
        shownScale = targetScale()
    }

    override fun setViewport(width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
    }

    override fun getTransform(): Transform {
        val handoff = checkHandoff()
        firstPerson?.takeIf { following && it.ready }?.let { return it.cameraToWorld.toTransform() }
        return handoff?.transform(orbitTransform()) ?: orbitTransform()
    }

    /** The camera's model matrix on the orbit, [pose] as drawn. */
    private fun orbitTransform(): Transform {
        // Drawn from further back while a [squeeze] holds the picture smaller: the orbit is not
        // touched, so the picture comes back whole when the squeeze goes.
        val drawn = pose.copy(distance = pose.distance / shownScale)
        val eye = CameraRig.eye(drawn)
        // A pedestal move along the camera's own up axis: the picture slides, the orbit does not.
        val drop = liftDrop(drawn, shownLift)
        return Transform(lookAt(eye = eye - drop, target = pose.target - drop, up = Float3(0f, 1f, 0f)))
    }

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        takeOver()
        grabbing = true
        panning = strafe
        lastX = x
        lastY = y
    }

    override fun grabUpdate(x: Int, y: Int) {
        val dx = (x - lastX).toFloat()
        val dy = (y - lastY).toFloat()
        lastX = x
        lastY = y
        if (dx == 0f && dy == 0f) return
        if (panning) {
            pose = bounded(pose.copy(target = pannedTarget(dx, dy)))
            return
        }
        val perPixel = degreesPerPixel
        val next = clampDrag(
            pose.copy(
                azimuthDegrees = pose.azimuthDegrees - dx * perPixel,
                elevationDegrees = pose.elevationDegrees - dy * perPixel,
            )
        )
        val turned = abs(next.azimuthDegrees - pose.azimuthDegrees) + abs(next.elevationDegrees - pose.elevationDegrees)
        dragAzimuth += next.azimuthDegrees - pose.azimuthDegrees
        dragElevation += next.elevationDegrees - pose.elevationDegrees
        pose = slidTowardsPivot(next, turned)
    }

    /**
     * [next] with its target moved part of the way onto [pivot], in proportion to the [turned]
     * degrees: the touch-down keeps the picture ([repivoted]), and the first
     * [PIVOT_SLIDE_DEGREES] of the drag carry the orbit's centre onto the cloud, so no angle
     * turns it off screen.
     */
    private fun slidTowardsPivot(next: OrbitPose, turned: Float): OrbitPose {
        val centre = pivot ?: return next
        val share = (turned / PIVOT_SLIDE_DEGREES).coerceIn(0f, 1f)
        return next.copy(target = next.target + (centre - next.target) * share)
    }

    // The gesture detector ends every gesture here, a pinch included: it never calls scrollEnd.
    override fun grabEnd() {
        grabbing = false
        panning = false
        pinching = false
    }

    override fun scrollBegin(x: Int, y: Int, separation: Float) {
        takeOver()
        pinching = true
    }

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
        pose = bounded(
            pose.copy(
                distance = zoomedDistanceForPinch(
                    distance = pose.distance,
                    prevSeparation = prevSeparation,
                    currSeparation = currSeparation,
                    minDistance = ArDebugFraming.MIN_DISTANCE,
                    maxDistance = ArDebugFraming.MAX_DISTANCE,
                )
            )
        )
    }

    override fun scrollEnd() {
        pinching = false
    }

    override fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) {
        recenter()
        recenteredByTap = true
    }

    override fun update(deltaTime: Float) {
        recenteredByTap = false
        val dt = deltaTime.takeIf { it.isFinite() && it > 0f && it < 0.25f } ?: return
        checkHandoff()?.let { it.seconds += dt }
        if (grabbing && !panning) {
            val spin = maxSpin
            azimuthVelocity = (dragAzimuth / dt).coerceIn(-spin, spin)
            elevationVelocity = (dragElevation / dt).coerceIn(-spin, spin)
        }
        dragAzimuth = 0f
        dragElevation = 0f
        easeSqueeze(dt)
        if (grabbing) return

        if (following) {
            val from = introFrom
            if (from != null) {
                if (!introHeld) introSeconds += dt
                val progress = introSeconds / ReplayIntro.DURATION_S
                pose = ArDebugFraming.clamp(ReplayIntro.pose(from, home, progress))
                if (progress >= 1f) introFrom = null
                return
            }
            // A picked object holds the view still on its heading while its box is read; the sway
            // starts again from its still point once it is let go.
            if (steady) followSeconds = 0f else followSeconds += dt
            val sway = if (drift && !overhead && !steady) ArDebugFraming.sway(followSeconds) else 0f
            home = home.copy(azimuthDegrees = heading + sway)
            pose = ArDebugFraming.approach(pose, home, dt, approachRate, maxTurnDegreesPerSecond)
            return
        }

        glide?.let { to ->
            pose = ArDebugFraming.approach(pose, to, dt)
            if (pose == to) glide = null
            return
        }

        if (azimuthVelocity != 0f || elevationVelocity != 0f) {
            val next = clampDrag(
                pose.copy(
                    azimuthDegrees = pose.azimuthDegrees + azimuthVelocity * dt,
                    elevationDegrees = pose.elevationDegrees + elevationVelocity * dt,
                )
            )
            // A coast into the pitch limit stops there instead of pressing on it.
            if (next.elevationDegrees != pose.elevationDegrees + elevationVelocity * dt) elevationVelocity = 0f
            val turned = abs(next.azimuthDegrees - pose.azimuthDegrees) +
                abs(next.elevationDegrees - pose.elevationDegrees)
            pose = slidTowardsPivot(next, turned)
            azimuthVelocity = CameraRig.decayInertia(azimuthVelocity, dt)
            elevationVelocity = CameraRig.decayInertia(elevationVelocity, dt)
        }
    }

    /** [shownLift] and [shownScale] eased towards the band the picture is drawn in, [SQUEEZE_RATE]. */
    private fun easeSqueeze(dt: Float) {
        easeFocus(dt)
        val lift = targetLift()
        val scale = targetScale()
        val fraction = 1f - exp(-SQUEEZE_RATE * dt)
        shownLift += (lift - shownLift) * fraction
        shownScale += (scale - shownScale) * fraction
        if (abs(lift - shownLift) < SQUEEZE_EPSILON) shownLift = lift
        if (abs(scale - shownScale) < SQUEEZE_EPSILON) shownScale = scale
    }

    /** [candidate] kept inside [limits]: what every gesture's result goes through. */
    private fun bounded(candidate: OrbitPose): OrbitPose =
        ArDebugFraming.clamp(candidate, limits, lift, verticalFovDegrees)

    /**
     * A cinematic move was cut short by a touch or a button (#4631): the camera takes over on the
     * move's last [shot], free, and coasts on at the move's speed — capped like a fling, so the
     * view never stops dead nor spins away.
     */
    override fun takeOver(shot: CameraShot, velocity: CameraShotVelocity) {
        // The same limits as a gesture: the coast that follows goes through them on its first frame.
        val landed = bounded(shot.toOrbitPose())
        // A move that ends on its own is handed over too, at rest: when it lands where the camera
        // is already following (ScanResultCinematics hands the orbit its framing first), it stays.
        if (following && velocity.isZero && landed.isNear(pose)) return
        pose = landed
        takeOver()
        val spin = maxSpin
        azimuthVelocity = velocity.azimuthDegreesPerSecond.coerceIn(-spin, spin)
        elevationVelocity = velocity.elevationDegreesPerSecond.coerceIn(-spin, spin)
    }

    private fun takeOver() {
        glide = null
        following = false
        introFrom = null
        azimuthVelocity = 0f
        elevationVelocity = 0f
        // Keep the heading continuous: the home angle becomes the current one, so the view the
        // finger leaves is the view that stays.
        heading = pose.azimuthDegrees
        home = home.copy(azimuthDegrees = heading)
        pivot?.let { pose = repivoted(it) }
    }

    private fun OrbitPose.isNear(other: OrbitPose): Boolean =
        length(target - other.target) < LANDED_EPSILON &&
            abs(azimuthDegrees - other.azimuthDegrees) < LANDED_EPSILON &&
            abs(elevationDegrees - other.elevationDegrees) < LANDED_EPSILON &&
            abs(distance - other.distance) < LANDED_EPSILON

    /**
     * The fastest a release coasts at, in degrees per second: [MAX_SPIN], or the speed whose
     * decay ([CameraRig.decayInertia]) covers [maxCoastDegrees] in all.
     */
    val maxSpin: Float
        get() = maxCoastDegrees?.let { minOf(MAX_SPIN, it * -ln(CameraRig.INERTIA_DECAY_PER_SECOND)) } ?: MAX_SPIN

    /** The orbit's degrees per pixel of drag: [degreesPerViewWidth] over the width, or the fixed rate. */
    val degreesPerPixel: Float
        get() = degreesPerViewWidth?.let { it / viewportWidth } ?: DEGREES_PER_PIXEL

    /**
     * A drag's [next] pose: its pitch inside [elevationRange], then [bounded] — the room's limits
     * (eye above the floor, outside the scan) win over the range.
     */
    private fun clampDrag(next: OrbitPose): OrbitPose =
        bounded(next.copy(elevationDegrees = next.elevationDegrees.coerceIn(elevationRange)))

    /**
     * The pose that draws exactly the picture [pose] draws, orbiting around the point of the view
     * ray nearest [point] instead of around its own target: same eye, same direction, the target
     * slid along the ray to [point]'s depth (and up by the [lift] that depth draws it with).
     */
    fun repivoted(point: Position): OrbitPose {
        val forward = normalize(pose.target - CameraRig.eye(pose))
        val drawnEye = CameraRig.eye(pose) - liftDrop(pose)
        val depth = dot(point - drawnEye, forward).coerceIn(ArDebugFraming.MIN_DISTANCE, ArDebugFraming.MAX_DISTANCE)
        val moved = pose.copy(distance = depth)
        return moved.copy(target = drawnEye + forward * depth + liftDrop(moved))
    }

    /** How far [lift] moves the drawn camera of [at] along its own up axis. */
    /** [focusProgress] moved along the `motion-focus` timeline towards [focusBand]'s presence. */
    private fun easeFocus(dt: Float) {
        focusBand?.let { lastFocusBand = it }
        val goal = if (focusBand != null) 1f else 0f
        val step = dt * MILLIS_PER_SECOND / SceneViewTokens.Motion.focusMillis
        focusProgress = if (goal > focusProgress) {
            minOf(goal, focusProgress + step)
        } else {
            maxOf(goal, focusProgress - step)
        }
    }

    /** [focusProgress] through the `ease-expressive` curve: 0 = the chrome's band, 1 = full screen. */
    private fun focusShare(): Float = SceneViewTokens.Ease.expressive.transform(focusProgress)

    /** The lift the picture heads for: [squeeze]'s, else [band]'s blended towards [focusBand]'s. */
    private fun targetLift(): Float {
        squeeze?.let { return it.lift }
        val focus = lastFocusBand ?: return band.lift
        return band.lift + (focus.lift - band.lift) * focusShare()
    }

    /** The scale the picture heads for: [squeeze]'s, else 1 blended towards [focusScale]. */
    private fun targetScale(): Float {
        if (squeeze != null) return ArDebugFraming.squeezeScale(band, squeeze)
        if (lastFocusBand == null) return 1f
        return 1f + (focusScale - 1f) * focusShare()
    }

    private fun liftDrop(at: OrbitPose, lift: Float = shownLift): Float3 {
        val metresPerPixel = CameraRig.worldPerPixel(at.distance, verticalFovDegrees, viewportHeight)
        if (lift == 0f || metresPerPixel <= 0f) return Float3()
        val forward = normalize(at.target - CameraRig.eye(at))
        val up = cross(normalize(cross(forward, Float3(0f, 1f, 0f))), forward)
        return up * (lift * viewportHeight * metresPerPixel)
    }

    private fun pannedTarget(dx: Float, dy: Float): Position {
        val metresPerPixel = CameraRig.worldPerPixel(pose.distance, verticalFovDegrees, viewportHeight)
        if (metresPerPixel <= 0f) return pose.target
        val forward = normalize(pose.target - CameraRig.eye(pose))
        val right = normalize(cross(forward, Float3(0f, 1f, 0f)))
        val up = cross(right, forward)
        return pose.target - right * (dx * metresPerPixel) - up * (dy * metresPerPixel)
    }

    companion object {
        /** Same lens as every `SceneView` camera by default. */
        const val FOCAL_LENGTH_MM = 28.0
        const val DEGREES_PER_PIXEL = 0.28f
        const val MAX_SPIN = 360f

        /** The degrees of drag over which the orbit's centre slides onto [pivot]. */
        const val PIVOT_SLIDE_DEGREES = 30f

        /**
         * How fast the picture follows a [squeeze], per second: the settings sheet rises in some
         * 300 ms, and the picture keeps up with it instead of trailing behind.
         */
        const val SQUEEZE_RATE = 10f

        private const val MILLIS_PER_SECOND = 1000f

        /** Close enough to the squeeze's lift and scale to stand on them. */
        const val SQUEEZE_EPSILON = 1e-4f

        /** How close, in degrees and metres, a finished move must land to count as already there. */
        private const val LANDED_EPSILON = 1e-3f
    }
}

/**
 * The clear part of a view, where a session is framed: [halfWidth] and [halfHeight] are the
 * fractions of the view's half-width and half-height the session may reach (1 = the view's edge),
 * around a centre drawn [lift] of the view's height above its middle.
 *
 * It is what makes the framing hold on any screen: the fit is computed for the band the chrome
 * leaves, not for a sphere and a margin tuned on one phone.
 */
data class OrbitBand(val halfWidth: Float, val halfHeight: Float, val lift: Float = 0f) {
    companion object {
        /** The whole view in full screen (#4558): no chrome above or below, a stage's air at the sides. */
        val FULL_SCREEN = OrbitBand(halfWidth = 0.90f, halfHeight = 0.86f)

        /** A card or a picture-in-picture: nothing over it, a little air all round. */
        val CARD = OrbitBand(halfWidth = 0.86f, halfHeight = 0.86f)

        /** A full-screen stage held upright: the HUD above, the timeline and the dock below. */
        val STAGE_PORTRAIT = OrbitBand(halfWidth = 0.90f, halfHeight = 0.46f, lift = 0.04f)

        /** The same stage on its side: the chrome takes a larger share of a shorter view. */
        val STAGE_LANDSCAPE = OrbitBand(halfWidth = 0.60f, halfHeight = 0.50f, lift = 0.05f)

        /** The record screen's stage: the scan's figures above, the shutter below, room to grow. */
        val SCAN = OrbitBand(halfWidth = 0.78f, halfHeight = 0.40f, lift = 0.12f)

        /** The stage of a view of [aspect] (width / height). */
        fun stage(aspect: Float): OrbitBand = if (aspect > 1f) STAGE_LANDSCAPE else STAGE_PORTRAIT

        /** The share of a measured band the session fills: the rest is air against the chrome. */
        const val MEASURED_FILL = 0.92f

        /** Below this share of the view's height, a measured band is no stage at all. */
        const val MIN_MEASURED_SHARE = 0.2f

        /**
         * The band the chrome really leaves, measured on screen: in a view [viewHeight] tall, the
         * chrome above ends at [top] and the chrome below starts at [bottom] (one unit, from the
         * view's top edge). `null` while either is not laid out, or when they leave no stage
         * between them — the caller then keeps [stage].
         */
        fun between(
            top: Float,
            bottom: Float,
            viewHeight: Float,
            halfWidth: Float = STAGE_PORTRAIT.halfWidth,
        ): OrbitBand? {
            val share = (bottom - top) / viewHeight
            // NaN — a card not laid out yet — fails every comparison, so it is asked for by name.
            if (share.isNaN() || viewHeight <= 0f || halfWidth.isNaN()) return null
            if (top < 0f || bottom > viewHeight || share < MIN_MEASURED_SHARE) return null
            // Hundredths: a card that settles by a pixel does not move the camera.
            fun hundredths(value: Float) = (value * 100f).roundToInt() / 100f
            return OrbitBand(
                halfWidth = halfWidth,
                halfHeight = hundredths(share * MEASURED_FILL),
                lift = hundredths(0.5f - (top + bottom) / 2f / viewHeight),
            )
        }

        /** Below this half-width, what a side card leaves is no stage at all. */
        const val MIN_BESIDE_HALF_WIDTH = 0.2f

        /**
         * The half-width a card standing beside the session leaves it: in a view [viewWidth] wide
         * the card ends at [cardEnd] from the near edge, and the session — which stays centred —
         * may reach no closer to the centre's other side than that. `null` while the card is not
         * laid out, or when it leaves no stage.
         */
        fun halfWidthBeside(cardEnd: Float, viewWidth: Float): Float? {
            val half = (1f - 2f * cardEnd / viewWidth) * MEASURED_FILL
            if (half.isNaN() || viewWidth <= 0f || cardEnd < 0f) return null
            if (half < MIN_BESIDE_HALF_WIDTH) return null
            return (half * 100f).roundToInt() / 100f
        }

        /**
         * A phone on its side: the band between two cards standing on either side of the session —
         * one ends at [startCardEnd], the other starts at [endCardStart] — from [top], under the
         * status bar, down to [bottom], where the chrome under the session starts. The session
         * stays centred, so the card that reaches further in decides for both sides (a cutout
         * pushes one of them).
         */
        @Suppress("LongParameterList")
        fun betweenSides(
            startCardEnd: Float,
            endCardStart: Float,
            top: Float,
            bottom: Float,
            viewWidth: Float,
            viewHeight: Float,
        ): OrbitBand? {
            val cardEnd = maxOf(startCardEnd, viewWidth - endCardStart)
            val halfWidth = halfWidthBeside(cardEnd, viewWidth) ?: return null
            return between(top, bottom, viewHeight, halfWidth)
        }
    }
}

/**
 * What a gesture may do around one scene: the camera stays between [minDistance] and
 * [maxDistance], its pivot inside [bounds] (`[minX, minY, minZ, maxX, maxY, maxZ]`), its eye
 * outside them — never in the middle of the cloud — and above [floorY].
 */
class OrbitLimits(
    val minDistance: Float = ArDebugFraming.MIN_DISTANCE,
    val maxDistance: Float = ArDebugFraming.MAX_DISTANCE,
    val bounds: FloatArray? = null,
    val floorY: Float? = null,
) {
    companion object {
        /** Before any content: no bounds to hold, and a room-sized reach. */
        val NONE = OrbitLimits(maxDistance = ArDebugFraming.EMPTY_MAX_DISTANCE)
    }
}

/** Pure framing maths of [ArDebugOrbitCamera]: where "home" is, and how the camera gets there. */
@Suppress("TooManyFunctions")
object ArDebugFraming {
    const val MIN_DISTANCE = 0.25f

    /** The view's far plane (`CameraNode`'s default): nothing is drawn past it. */
    const val FAR_PLANE_M = 1000f

    /**
     * The furthest the eye may stand from its pivot. It is not a framing limit: a room is framed
     * from whatever distance shows it whole — a 20 × 9 m terrace from some 60 m, where a 40 m
     * stop cropped it (#4306) — and a pinch stops [MAX_ZOOM_OUT] past that ([limits]). This only
     * keeps what is framed inside [FAR_PLANE_M]: a box fitted from `d` reaches no further than
     * `d` behind its pivot.
     */
    const val MAX_DISTANCE = FAR_PLANE_M / 2f

    /** Before any content there is no room to keep whole: a pinch stays within a room's size. */
    const val EMPTY_MAX_DISTANCE = 40f

    /** Never under the horizon: below it a room is the underside of its own floor. */
    const val MIN_ELEVATION = 4f
    const val MAX_ELEVATION = 88f

    /** The three-quarter view from above-behind the Rerun viewer opens a spatial view with. */
    const val HOME_ELEVATION = 32f
    const val HOME_AZIMUTH = 35f

    /** The map's near-vertical view: a floor plan, with just enough tilt to keep depth. */
    const val MAP_ELEVATION = 84f

    /**
     * How much of its [OrbitBand] a session still growing fills: the rest is room for the next
     * metres of trail, so the camera is not pulling back at every step.
     */
    const val GROWING_FILL = 0.8f

    /** A session that barely moved still gets a room-sized view: its box is at least this wide. */
    const val MIN_HALF_EXTENT_M = 0.45f
    const val MIN_HALF_HEIGHT_M = 0.25f

    /** How far out a pinch may pull, as a multiple of the framing distance: the room stays a room. */
    const val MAX_ZOOM_OUT = 1.6f

    /** How close the eye may come to what was scanned, and to its floor. */
    const val EYE_CLEARANCE_M = 0.3f
    const val FLOOR_CLEARANCE_M = 0.15f

    /** The three-quarter turn off the scanner's own line of sight the replay opens on. */
    const val FRONT_OFFSET_DEGREES = 28f

    /** A path whose middle is nearer the room's than this names no side to look from. */
    const val FRONT_MIN_OFFSET_M = 0.35f

    /** The idle sway: this far each side of the heading, once every [SWAY_PERIOD_S]. */
    const val SWAY_DEGREES = 14f
    const val SWAY_PERIOD_S = 24f

    /** A room-sized framing for an empty session. */
    val DEFAULT_POSE = OrbitPose(
        target = Position(0f, -0.4f, -0.5f),
        azimuthDegrees = HOME_AZIMUTH,
        elevationDegrees = HOME_ELEVATION,
        distance = 3.2f,
    )

    /** Rate of the exponential approach to home, per second. ~95 % in one second. */
    const val APPROACH_RATE = 3f

    /** The fastest the automatic framing swings round the room: a half-turn takes over a second. */
    const val MAX_TURN_DEGREES_PER_SECOND = 150f

    /** [pose] inside the absolute limits: what a pose is held to before any scene is known. */
    fun clamp(pose: OrbitPose): OrbitPose = pose.copy(
        elevationDegrees = pose.elevationDegrees.coerceIn(MIN_ELEVATION, MAX_ELEVATION),
        distance = pose.distance.takeIf { it.isFinite() }?.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
            ?: DEFAULT_POSE.distance,
    )

    /**
     * [pose] inside [limits], for a camera drawn [lift] above its view's middle through a lens of
     * [verticalFovDegrees]: the pivot is brought back into the scene's bounds, the eye out of
     * them and above the floor, the distance under what still shows a room.
     */
    fun clamp(pose: OrbitPose, limits: OrbitLimits, lift: Float, verticalFovDegrees: Double): OrbitPose {
        val bounds = limits.bounds
        val target = if (bounds == null) {
            pose.target
        } else {
            Position(
                pose.target.x.coerceIn(bounds[0], bounds[3]),
                pose.target.y.coerceIn(max(bounds[1], limits.floorY ?: bounds[1]).coerceAtMost(bounds[4]), bounds[4]),
                pose.target.z.coerceIn(bounds[2], bounds[5]),
            )
        }
        val held = clamp(pose.copy(target = target))
        val far = limits.maxDistance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        fun reach(elevationDegrees: Float): Float {
            val near = max(limits.minDistance, exitDistance(bounds, held.copy(elevationDegrees = elevationDegrees)))
            // A scene too small to stand outside of at the far limit keeps the far limit.
            return held.distance.coerceIn(near.coerceAtMost(far), far)
        }
        val distance = reach(held.elevationDegrees)
        val floor = limits.floorY ?: return held.copy(distance = distance)
        val elevation = held.elevationDegrees.coerceAtLeast(
            minElevationAbove(floor + FLOOR_CLEARANCE_M - target.y, distance, lift, verticalFovDegrees)
        ).coerceAtMost(MAX_ELEVATION)
        return held.copy(elevationDegrees = elevation, distance = reach(elevation))
    }

    /**
     * How far the eye of [pose] must stand from its pivot to be [EYE_CLEARANCE_M] outside [bounds]:
     * the way out of the box along the camera's own line, 0 without bounds.
     */
    fun exitDistance(bounds: FloatArray?, pose: OrbitPose): Float {
        bounds ?: return 0f
        val azimuth = Math.toRadians(pose.azimuthDegrees.toDouble())
        val elevation = Math.toRadians(pose.elevationDegrees.toDouble())
        val direction = doubleArrayOf(cos(elevation) * sin(azimuth), sin(elevation), cos(elevation) * cos(azimuth))
        val origin = floatArrayOf(pose.target.x, pose.target.y, pose.target.z)
        var exit = Double.MAX_VALUE
        for (axis in 0..2) {
            val d = direction[axis]
            if (abs(d) < 1e-6) continue
            val wall = if (d > 0) bounds[axis + 3] else bounds[axis]
            exit = minOf(exit, (wall - origin[axis]) / d)
        }
        if (exit == Double.MAX_VALUE) return 0f
        return (exit.coerceAtLeast(0.0) + EYE_CLEARANCE_M).toFloat()
    }

    /**
     * The lowest elevation, in degrees, at which a camera [distance] from its pivot keeps its eye
     * [height] metres above that pivot — the lens itself, which a view lifted by [lift] draws
     * lower than the orbit's eye. Under [MIN_ELEVATION] when the pivot is high enough already.
     */
    fun minElevationAbove(height: Float, distance: Float, lift: Float, verticalFovDegrees: Double): Float {
        if (!distance.isFinite() || distance <= 0f) return MIN_ELEVATION
        // The pedestal drops the lens by 2·lift·d·tan(fov/2) along the camera's up axis.
        val k = 2.0 * lift * tan(Math.toRadians(verticalFovDegrees / 2.0))
        val reach = (height / distance / sqrt(1.0 + k * k)).coerceIn(-1.0, 1.0)
        val elevation = Math.toDegrees(atan(k) + asin(reach)).toFloat()
        return elevation.coerceIn(MIN_ELEVATION, MAX_ELEVATION)
    }

    /** What the gestures may do around [bounds], framed from [home], with its floor at [floorY]. */
    fun limits(bounds: FloatArray?, home: OrbitPose, floorY: Float?): OrbitLimits {
        bounds ?: return OrbitLimits.NONE
        return OrbitLimits(
            maxDistance = (home.distance * MAX_ZOOM_OUT).coerceIn(MIN_DISTANCE, MAX_DISTANCE),
            bounds = roomSized(bounds),
            floorY = floorY,
        )
    }

    /**
     * The pose that frames [bounds] (`[minX, minY, minZ, maxX, maxY, maxZ]`, or null for an empty
     * session) at [azimuthDegrees], through a lens of [verticalFovDegrees] on a view of
     * [aspect] (width / height).
     *
     * The fit is exact: the box's eight corners are projected, and the camera stands at the
     * distance where the outermost one touches the [band]'s edge — then the pivot slides so the
     * box sits in the band's middle. A tall phone, the same phone on its side and a small card
     * all see the whole room, filling the clear part of the view. [fill] under 1 leaves room for
     * a session still growing.
     *
     * [also] (flat xyz) is fitted with the box: what is drawn around the room and has to be read
     * too — its dimensions, which stand outside its walls.
     */
    @Suppress("LongParameterList")
    fun home(
        bounds: FloatArray?,
        azimuthDegrees: Float,
        verticalFovDegrees: Double,
        aspect: Float,
        elevationDegrees: Float = HOME_ELEVATION,
        band: OrbitBand = OrbitBand.CARD,
        fill: Float = 1f,
        also: FloatArray? = null,
    ): OrbitPose {
        if (bounds == null) {
            return DEFAULT_POSE.copy(azimuthDegrees = azimuthDegrees, elevationDegrees = elevationDegrees)
        }
        val box = roomSized(bounds)
        val azimuth = Math.toRadians(azimuthDegrees.toDouble())
        val elevation = Math.toRadians(elevationDegrees.coerceIn(MIN_ELEVATION, MAX_ELEVATION).toDouble())
        // The camera's frame: `back` runs from the pivot to the eye, `right` and `up` span the picture.
        val back = doubleArrayOf(cos(elevation) * sin(azimuth), sin(elevation), cos(elevation) * cos(azimuth))
        val right = doubleArrayOf(cos(azimuth), 0.0, -sin(azimuth))
        val up = doubleArrayOf(-sin(azimuth) * sin(elevation), cos(elevation), -cos(azimuth) * sin(elevation))
        val tanV = tan(Math.toRadians(verticalFovDegrees / 2.0))
        val tanH = tanV * aspect.coerceIn(0.2f, 5f)
        val share = fill.coerceIn(0.2f, 1f)
        val halfX = (band.halfWidth * share).coerceIn(0.05f, 1f).toDouble()
        val halfY = (band.halfHeight * share).coerceIn(0.05f, 1f).toDouble()
        val centreY = 2.0 * band.lift

        val target = doubleArrayOf((box[0] + box[3]) / 2.0, (box[1] + box[4]) / 2.0, (box[2] + box[5]) / 2.0)
        var distance = 0.0
        repeat(FIT_PASSES) { pass ->
            distance = MIN_DISTANCE.toDouble()
            forEachPoint(box, also, target, right, up, back) { x, y, z ->
                distance = maxOf(
                    distance,
                    z + MIN_DISTANCE,
                    z + abs(x) / (halfX * tanH),
                    (y + (centreY + halfY) * tanV * z) / (halfY * tanV),
                    (-y + (halfY - centreY) * tanV * z) / (halfY * tanV),
                )
            }
            if (pass == FIT_PASSES - 1) return@repeat
            // Where the box lands in the picture at that distance, and the slide that centres it.
            var left = Double.MAX_VALUE
            var rightmost = -Double.MAX_VALUE
            var bottom = Double.MAX_VALUE
            var top = -Double.MAX_VALUE
            forEachPoint(box, also, target, right, up, back) { x, y, z ->
                val depth = distance - z
                val px = x / (depth * tanH)
                val py = (y + centreY * distance * tanV) / (depth * tanV)
                left = minOf(left, px)
                rightmost = maxOf(rightmost, px)
                bottom = minOf(bottom, py)
                top = maxOf(top, py)
            }
            val slideX = (left + rightmost) / 2.0 * distance * tanH
            val slideY = ((bottom + top) / 2.0 - centreY) * distance * tanV
            for (axis in 0..2) target[axis] += right[axis] * slideX + up[axis] * slideY
        }
        return clamp(
            OrbitPose(
                target = Position(target[0].toFloat(), target[1].toFloat(), target[2].toFloat()),
                azimuthDegrees = azimuthDegrees,
                elevationDegrees = elevationDegrees,
                distance = distance.toFloat(),
            )
        )
    }

    /**
     * [home] for a room whose dimensions are written round it: [measure], drawn on the floor at
     * [floorY] in screen pixels of a view [viewportHeightPx] tall. The figures stand outside the
     * walls, so a fit on the room alone ran them off the screen's edge; they are fitted with it.
     *
     * Their size in metres follows the distance they are seen from, which they push back in
     * turn: [MEASURE_PASSES] rounds settle it, each a tenth of the last.
     */
    @Suppress("LongParameterList")
    fun homeWithMeasure(
        bounds: FloatArray?,
        measure: RoomMeasure?,
        floorY: Float,
        viewportHeightPx: Int,
        azimuthDegrees: Float,
        verticalFovDegrees: Double,
        aspect: Float,
        elevationDegrees: Float = HOME_ELEVATION,
        band: OrbitBand = OrbitBand.CARD,
        fill: Float = 1f,
    ): OrbitPose {
        var pose = home(bounds, azimuthDegrees, verticalFovDegrees, aspect, elevationDegrees, band, fill)
        // A view not laid out yet has no pixels to size the figures in.
        if (bounds == null || measure == null || viewportHeightPx <= 1) return pose
        repeat(MEASURE_PASSES) {
            val perPixel = CameraRig.worldPerPixel(pose.distance, verticalFovDegrees, viewportHeightPx)
            val eye = CameraRig.eye(pose)
            val reach = MeasureDrawing.reach(
                measure, MeasureDrawing.sidesFacing(measure, eye.x, eye.z), floorY, MeasureSize.atMost(perPixel),
            )
            pose = home(bounds, azimuthDegrees, verticalFovDegrees, aspect, elevationDegrees, band, fill, also = reach)
        }
        return pose
    }

    /**
     * The side a session is best seen from, as an azimuth: the camera stands on the side the
     * person who scanned it walked ([trail], flat `[x,y,z, …]`), a three-quarter turn off their
     * own line of sight — the surfaces they captured face it. [fallback] when the path circles
     * the middle of [bounds] and names no side.
     */
    fun frontAzimuth(bounds: FloatArray?, trail: FloatArray, fallback: Float = HOME_AZIMUTH): Float {
        val count = trail.size / 3
        if (bounds == null || count == 0) return fallback
        var x = 0.0
        var z = 0.0
        for (i in 0 until count) {
            x += trail[i * 3]
            z += trail[i * 3 + 2]
        }
        val dx = (x / count - (bounds[0] + bounds[3]) / 2.0).toFloat()
        val dz = (z / count - (bounds[2] + bounds[5]) / 2.0).toFloat()
        if (hypot(dx, dz) < FRONT_MIN_OFFSET_M) return fallback
        return Math.toDegrees(atan2(dx, dz).toDouble()).toFloat() + FRONT_OFFSET_DEGREES
    }

    /**
     * The map's heading: of the four that square the room's walls ([roomYawDegrees]) with the
     * screen, the nearest to [currentAzimuth] — never more than an eighth of a turn away. Without
     * a room to square, the camera keeps its heading.
     */
    fun mapAzimuth(currentAzimuth: Float, roomYawDegrees: Float?): Float {
        roomYawDegrees ?: return currentAzimuth
        val off = CameraRig.shortestDelta(roomYawDegrees, currentAzimuth)
        val quarter = Math.round(off / QUARTER_TURN) * QUARTER_TURN
        return currentAzimuth - (off - quarter)
    }

    /** The idle sway's offset from the heading, in degrees, [seconds] into following: starts still. */
    fun sway(seconds: Float): Float =
        SWAY_DEGREES * sin(2.0 * Math.PI * seconds / SWAY_PERIOD_S).toFloat()

    /**
     * The closest pose at [azimuthDegrees] and [elevationDegrees] that shows the whole of [bounds]
     * (`[minX, minY, minZ, maxX, maxY, maxZ]`) inside what [insets] leave free of a view of
     * [aspect] (width / height), through a lens of [verticalFovDegrees] (#4492).
     *
     * Where [home] fits a sphere around the content and shortens the result by a margin, this
     * projects the box's eight corners: a room is a flat box seen from above at an angle, and the
     * sphere says nothing of how wide it lands on a tall screen — a squarish room opened with a
     * side past the screen's edge. The distance is the least one at which every corner projects
     * inside the free area, on both axes; the target then slides in the picture plane so the
     * projected box is centred there, since perspective draws the near half of a box larger than
     * the far one and chrome is rarely the same height top and bottom.
     */
    fun fit(
        bounds: FloatArray,
        azimuthDegrees: Float,
        elevationDegrees: Float,
        verticalFovDegrees: Double,
        aspect: Float,
        insets: FramingInsets = FramingInsets.None,
    ): OrbitPose {
        val centre = Position(
            (bounds[0] + bounds[3]) / 2f,
            (bounds[1] + bounds[4]) / 2f,
            (bounds[2] + bounds[5]) / 2f,
        )
        val corners = List(BOX_CORNERS) { corner ->
            Position(
                bounds[if (corner and 1 == 0) 0 else 3],
                bounds[if (corner and 2 == 0) 1 else 4],
                bounds[if (corner and 4 == 0) 2 else 5],
            )
        }
        val from = OrbitPose(centre, azimuthDegrees, elevationDegrees.coerceIn(MIN_ELEVATION, MAX_ELEVATION))
        val view = Framed(corners, from, verticalFovDegrees, aspect, insets)
        val framed = clamp(from.copy(distance = view.distance()))
        return framed.copy(
            target = centre + view.right * view.horizontal.slide(framed.distance) +
                view.up * view.vertical.slide(framed.distance),
        )
    }

    /**
     * [pose] moved as little as it takes to show the whole of [corners] — a picked object's box —
     * inside what one of [insets] leaves free of the view: [pose] itself when it already does,
     * else the same angles with the picture slid, and stepped back only for a box the free area
     * is too small for at this distance. Never closer: the room keeps the size the finger left it.
     *
     * Each of [insets] is one way round the chrome — under a card in a corner, or beside it —
     * and the one that moves the camera least wins. [lift] is [ArDebugOrbitCamera.lift]: the
     * picture is drawn that share of the view's height above where the pose alone would put it.
     *
     * The bands are taken as they are, down to [REVEAL_MIN_FREE] of the view: [fit]'s floor of
     * [FramingInsets.MIN_FREE] cut back any pair covering more than four fifths of the height,
     * which on a small phone is the way under the card — the box was then found "clear" of bands
     * thinner than the chrome, and left behind it. A way round that leaves less than the floor is
     * not taken while another does; through a thin band the camera steps back until the box is
     * small enough to be whole in it.
     */
    fun reveal(
        pose: OrbitPose,
        corners: List<Position>,
        verticalFovDegrees: Double,
        aspect: Float,
        insets: List<FramingInsets>,
        lift: Float = 0f,
    ): OrbitPose {
        if (corners.isEmpty()) return pose
        var best = pose
        var bestCost = Float.MAX_VALUE
        val open = insets.filter { it.leaves(REVEAL_MIN_FREE) }.ifEmpty { insets }
        for (free in open) {
            val view = Framed(corners, pose, verticalFovDegrees, aspect, free, REVEAL_MIN_FREE)
            val distance = clamp(pose.copy(distance = max(pose.distance, view.distance()))).distance
            val across = view.horizontal.nudge(distance, raised = 0f)
            val upward = view.vertical.nudge(distance, raised = lift * 2f * view.tanVertical * distance)
            val cost = distance - pose.distance + abs(across) + abs(upward)
            if (cost < bestCost) {
                bestCost = cost
                best = if (cost == 0f) {
                    pose
                } else {
                    pose.copy(target = pose.target + view.right * across + view.up * upward, distance = distance)
                }
            }
        }
        return best
    }

    /**
     * [points] as a camera at [from]'s angles sees them from its target: each one's offset across
     * the picture, up it and away, and the two screen axes [insets] leave them.
     */
    private class Framed(
        points: List<Position>,
        from: OrbitPose,
        verticalFovDegrees: Double,
        aspect: Float,
        insets: FramingInsets,
        minFree: Float = FramingInsets.MIN_FREE,
    ) {
        val right: Float3
        val up: Float3
        val tanVertical = tan(Math.toRadians(verticalFovDegrees / 2.0)).toFloat()
        val horizontal: FitAxis
        val vertical: FitAxis
        private val nearest: Float

        init {
            val azimuth = Math.toRadians(from.azimuthDegrees.toDouble())
            val tilt = Math.toRadians(from.elevationDegrees.toDouble())
            val sinA = sin(azimuth).toFloat()
            val cosA = cos(azimuth).toFloat()
            val sinE = sin(tilt).toFloat()
            val cosE = cos(tilt).toFloat()
            // The camera's own axes at these angles, as CameraRig.eye and lookAt make them.
            right = Float3(cosA, 0f, -sinA)
            up = Float3(-sinA * sinE, cosE, -cosA * sinE)
            val forward = Float3(-cosE * sinA, -sinE, -cosE * cosA)
            val across = FloatArray(points.size) { dot(points[it] - from.target, right) }
            val upward = FloatArray(points.size) { dot(points[it] - from.target, up) }
            val away = FloatArray(points.size) { dot(points[it] - from.target, forward) }
            val tanHorizontal = tanVertical * aspect.coerceIn(0.2f, 5f)
            val free = insets.bounded(minFree)
            horizontal = FitAxis(across, away, tanHorizontal, -1f + 2f * free.left, 1f - 2f * free.right)
            vertical = FitAxis(upward, away, tanVertical, -1f + 2f * free.bottom, 1f - 2f * free.top)
            // Never closer than the nearest point: every one stays in front of the lens.
            nearest = MIN_DISTANCE - away.min()
        }

        /** The least distance at which every point fits the free area, on both axes. */
        fun distance(): Float = maxOf(nearest, horizontal.distance(), vertical.distance())
    }

    /**
     * One screen axis of [fit]: each corner's offset [along] it and its depth [away] past the
     * target, the tangent of half the field of view on this axis ([tanHalf]), and the free band
     * in normalised device coordinates, from [low] to [high] (-1 and 1 are the view's edges).
     */
    private class FitAxis(
        private val along: FloatArray,
        private val away: FloatArray,
        private val tanHalf: Float,
        private val low: Float,
        private val high: Float,
    ) {
        /**
         * The least distance at which the corners fit the band, wherever the target slides to.
         * Corner `i` stays under [high] when the slide is at least `along[i] - high·tan·(d + away[i])`
         * and corner `j` over [low] when it is at most `along[j] - low·tan·(d + away[j])`: a slide
         * exists once every such floor is under every such ceiling, which is linear in `d`.
         */
        fun distance(): Float {
            var distance = 0f
            for (i in along.indices) for (j in along.indices) {
                val needed = (along[i] - along[j] + tanHalf * (low * away[j] - high * away[i])) /
                    ((high - low) * tanHalf)
                distance = max(distance, needed)
            }
            return distance
        }

        /**
         * How far the target moves along this axis to centre the corners in the band at [distance].
         * On the axis that set the distance there is one slide that fits; on the other there is a
         * range, and the one that puts the corners' two extremes the same way from the band's
         * edges is found by halving it — both extremes move the same way as the target slides.
         */
        fun slide(distance: Float): Float {
            var floor = -Float.MAX_VALUE
            var ceiling = Float.MAX_VALUE
            for (i in along.indices) {
                floor = max(floor, along[i] - high * tanHalf * (distance + away[i]))
                ceiling = minOf(ceiling, along[i] - low * tanHalf * (distance + away[i]))
            }
            if (floor >= ceiling) return (floor + ceiling) / 2f
            repeat(CENTRING_STEPS) {
                val middle = (floor + ceiling) / 2f
                var least = Float.MAX_VALUE
                var most = -Float.MAX_VALUE
                for (i in along.indices) {
                    val drawn = (along[i] - middle) / (tanHalf * (distance + away[i]))
                    least = minOf(least, drawn)
                    most = max(most, drawn)
                }
                if (least + most > low + high) floor = middle else ceiling = middle
            }
            return (floor + ceiling) / 2f
        }

        /**
         * The least the target moves along this axis for the corners to be in the band at
         * [distance]: nothing when they already are. [raised] is how far the picture is drawn up
         * this axis from where the target alone would put it, in metres at the target's depth.
         */
        fun nudge(distance: Float, raised: Float): Float {
            var floor = -Float.MAX_VALUE
            var ceiling = Float.MAX_VALUE
            for (i in along.indices) {
                floor = max(floor, along[i] + raised - high * tanHalf * (distance + away[i]))
                ceiling = minOf(ceiling, along[i] + raised - low * tanHalf * (distance + away[i]))
            }
            return if (floor >= ceiling) (floor + ceiling) / 2f else 0f.coerceIn(floor, ceiling)
        }
    }

    private const val BOX_CORNERS = 8
    private const val CENTRING_STEPS = 24

    /** The thinnest band of a view [reveal] brings a box into: a few rows, stepped back for. */
    const val REVEAL_MIN_FREE = 0.04f

    /**
     * One frame of the ease from [pose] to [home]: an exponential approach, which is frame-rate
     * independent and retargets smoothly — home moves every time the trail grows. The heading
     * turns at [MAX_TURN_DEGREES_PER_SECOND] at most: a recenter from the far side swings round,
     * it does not whip.
     */
    fun approach(
        pose: OrbitPose,
        home: OrbitPose,
        deltaSeconds: Float,
        rate: Float = APPROACH_RATE,
        maxTurnDegreesPerSecond: Float = MAX_TURN_DEGREES_PER_SECOND,
    ): OrbitPose {
        val fraction = 1f - exp(-rate * deltaSeconds)
        val eased = CameraRig.lerp(pose, home, fraction)
        val turn = maxTurnDegreesPerSecond * deltaSeconds
        val next = eased.copy(
            azimuthDegrees = pose.azimuthDegrees + (eased.azimuthDegrees - pose.azimuthDegrees).coerceIn(-turn, turn),
        )
        // Snap when close, so an idle view reaches a fixed point (render-on-demand can rest).
        val angleSettled = abs(CameraRig.shortestDelta(next.azimuthDegrees, home.azimuthDegrees)) < 0.01f &&
            abs(next.elevationDegrees - home.elevationDegrees) < 0.01f
        val targetOffset = abs(next.target.x - home.target.x) + abs(next.target.y - home.target.y) +
            abs(next.target.z - home.target.z)
        val reachSettled = abs(next.distance - home.distance) < 1e-4f && targetOffset < 1e-4f
        return if (angleSettled && reachSettled) home else next
    }

    /**
     * The scale that draws a picture fitted for [band] inside [squeeze] — the band a settings
     * sheet leaves (#4564): the smaller of the two half-extents' ratios, never above 1, and 1 with
     * no squeeze. The picture is scaled about the band's centre, which moves to the squeeze's.
     */
    fun squeezeScale(band: OrbitBand, squeeze: OrbitBand?): Float {
        if (squeeze == null || band.halfHeight <= 0f || band.halfWidth <= 0f) return 1f
        return minOf(1f, squeeze.halfHeight / band.halfHeight, squeeze.halfWidth / band.halfWidth)
            .coerceAtLeast(MIN_SQUEEZE_SCALE)
    }


    /** The smallest a squeeze draws the picture: under it the room is a speck, better left covered. */
    const val MIN_SQUEEZE_SCALE = 0.3f

    /** [bounds] widened about its middle to at least a room's size: a session that barely moved. */
    private fun roomSized(bounds: FloatArray): FloatArray {
        val out = bounds.copyOf()
        for (axis in 0..2) {
            val least = if (axis == 1) MIN_HALF_HEIGHT_M else MIN_HALF_EXTENT_M
            val middle = (bounds[axis] + bounds[axis + 3]) / 2f
            val half = max(least, (bounds[axis + 3] - bounds[axis]) / 2f)
            out[axis] = middle - half
            out[axis + 3] = middle + half
        }
        return out
    }

    /**
     * Each corner of [box], then each point of [also] (flat xyz), in the camera's frame about
     * [target]: across, up, and towards the eye.
     */
    @Suppress("LongParameterList")
    private inline fun forEachPoint(
        box: FloatArray,
        also: FloatArray?,
        target: DoubleArray,
        right: DoubleArray,
        up: DoubleArray,
        back: DoubleArray,
        visit: (x: Double, y: Double, z: Double) -> Unit,
    ) {
        val extra = (also?.size ?: 0) / 3
        for (point in 0 until CORNERS + extra) {
            val px: Double
            val py: Double
            val pz: Double
            if (point < CORNERS) {
                px = box[if (point and 1 == 0) 0 else 3] - target[0]
                py = box[if (point and 2 == 0) 1 else 4] - target[1]
                pz = box[if (point and 4 == 0) 2 else 5] - target[2]
            } else {
                val at = (point - CORNERS) * 3
                px = also!![at] - target[0]
                py = also[at + 1] - target[1]
                pz = also[at + 2] - target[2]
            }
            visit(
                px * right[0] + py * right[1] + pz * right[2],
                px * up[0] + py * up[1] + pz * up[2],
                px * back[0] + py * back[1] + pz * back[2],
            )
        }
    }

    private const val CORNERS = 8
    private const val FIT_PASSES = 4
    private const val MEASURE_PASSES = 3
    private const val QUARTER_TURN = 90f
}

/**
 * What covers a view, as fractions of its width ([left], [right]) and of its height ([top],
 * [bottom]) measured inwards from each edge: the chrome [ArDebugFraming.fit] keeps the content
 * clear of.
 */
data class FramingInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    /**
     * These insets, never negative, and leaving at least [minFree] of each axis free: two bands
     * that add up to more than that are cut back in proportion.
     */
    fun bounded(minFree: Float = MIN_FREE): FramingInsets {
        val (boundedLeft, boundedRight) = boundedPair(left, right, minFree)
        val (boundedTop, boundedBottom) = boundedPair(top, bottom, minFree)
        return FramingInsets(boundedLeft, boundedTop, boundedRight, boundedBottom)
    }

    /** Whether these insets leave [minFree] of each axis free as they are, with nothing cut back. */
    fun leaves(minFree: Float): Boolean = bounded(minFree) == bounded(0f)

    private fun boundedPair(first: Float, second: Float, minFree: Float): Pair<Float, Float> {
        val a = first.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val b = second.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val covered = a + b
        val most = 1f - minFree
        return if (covered <= most) a to b else a * most / covered to b * most / covered
    }

    companion object {
        val None = FramingInsets()

        /** The least share of a view's width or height a framing is given. */
        const val MIN_FREE = 0.2f
    }
}
