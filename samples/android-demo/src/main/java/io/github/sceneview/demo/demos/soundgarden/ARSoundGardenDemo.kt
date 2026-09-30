package io.github.sceneview.demo.demos.soundgarden

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.audio.AudioFalloff
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.GlassSurface
import io.github.sceneview.material.setColor
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.node.Node
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * AR demo — **Sound Garden**: four glowing orbs planted on the floor, each playing one part of
 * the same short song (bells, beat, pad, bass). Walking between them remixes the song: the orb
 * you walk up to gets louder, the ones you turn your back to sound muffled, and left and right
 * are heard as *places*, not as a balance knob.
 *
 * Everything the ear gets is computed from two poses per frame — the camera's and the anchor's
 * — by [SpatialVoiceMath] (level, interaural delay, rear low-pass, distance) and mixed
 * sample-locked by [SpatialMixCore] into one stereo [android.media.AudioTrack]. No native
 * library, no platform spatializer: it runs the same on a Pixel 4a as on a Pixel 9, and every
 * cue is unit-tested.
 *
 * Every orb pulses with the loudness of its own part (a precomputed RMS envelope indexed by
 * what the device has actually played), and every note it plays sends a shell out of it — the
 * eye finds the sound the ear hears.
 *
 * The garden plants itself on the lowest tracked floor plane, 1.6 m ahead, facing the user —
 * no tap to learn. Tapping an orb (or its legend chip) mutes its part.
 */
@Composable
fun ARSoundGardenDemo(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val arPlaybackDataset = rememberArPlaybackDataset()

    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }

    // ── Sound ────────────────────────────────────────────────────────────────────────────
    var stems by remember { mutableStateOf<List<FloatArray>?>(null) }
    var stemsFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.Default) {
                ORBS.map { StemDecoder.decodeMono(context.assets, it.asset) }
            }
        }.onSuccess { stems = it }
            .onFailure {
                Log.e(TAG, "Sound Garden stems failed to decode", it)
                stemsFailed = true
            }
    }
    val envelopes = remember(stems) { stems?.map { SoundGardenStems.envelope(it) } }
    val lastOnsets = remember(envelopes) { envelopes?.map { SoundGardenStems.lastOnsets(it) } }
    val mix = remember(stems) { stems?.let { SpatialMixCore(SoundGardenStems.SAMPLE_RATE, it) } }
    val audio = remember(mix) { mix?.let { SoundGardenAudio(it) } }
    DisposableEffect(audio) {
        onDispose { audio?.close() }
    }
    // Silence with the screen: a garden that keeps singing from the recents list is a bug.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, audio) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> audio?.pause()
                Lifecycle.Event.ON_START -> audio?.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val headphones = rememberHeadphonesConnected(context)

    // ── Garden state ─────────────────────────────────────────────────────────────────────
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var plantedAtNanos by remember { mutableLongStateOf(0L) }
    val muted = remember { mutableStateListOf(*Array(ORBS.size) { false }) }
    var visual by remember { mutableStateOf(GardenVisual.EMPTY) }
    val motion = remember { GardenMotion(ORBS.size) }

    fun replant() {
        anchor?.detach()
        anchor = null
        ORBS.indices.forEach { mix?.setTarget(it, VoiceParams.SILENT) }
        visual = GardenVisual.EMPTY
    }

    // After planting: two short hints, then the screen belongs to the garden.
    var hint by remember { mutableIntStateOf(HINT_NONE) }
    LaunchedEffect(anchor) {
        if (anchor == null) {
            hint = HINT_NONE
            return@LaunchedEffect
        }
        hint = HINT_WALK
        delay(HINT_DURATION_MS)
        hint = HINT_TURN
        delay(HINT_DURATION_MS)
        hint = HINT_NONE
    }

    val onSessionUpdated = { session: Session, frame: Frame ->
        val camera = frame.camera
        if (camera.trackingState == TrackingState.TRACKING) {
            val cameraPose = camera.displayOrientedPose
            val listener = SpatialVoiceMath.listenerFrame(
                cameraPosition = Float3(cameraPose.tx(), cameraPose.ty(), cameraPose.tz()),
                cameraForward = cameraPose.zAxis.let { Float3(-it[0], -it[1], -it[2]) },
                cameraUp = cameraPose.yAxis.let { Float3(it[0], it[1], it[2]) },
            )
            if (anchor == null && (stems != null || stemsFailed)) {
                plant(session, listener)?.let { planted ->
                    anchor = planted
                    plantedAtNanos = System.nanoTime()
                    audio?.start()
                }
            }
            anchor?.let { garden ->
                visual = updateGarden(
                    garden = garden,
                    listener = listener,
                    plantedAtNanos = plantedAtNanos,
                    muted = muted,
                    motion = motion,
                    mix = mix,
                    envelopes = envelopes,
                    lastOnsets = lastOnsets,
                    playedFrames = audio?.playedFrames ?: 0L,
                )
            }
        }
    }

    val partNames = ORBS.map { stringResource(it.nameRes) }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_sound_garden_title),
        onBack = onBack,
        controls = if (DemoSettings.qaMode) {
            { ForceTrackingFailureMenu() }
        } else {
            null
        },
        topOverlay = {
            if (anchor != null) {
                GardenLegend(
                    names = partNames,
                    muted = muted,
                    onToggle = { index -> muted[index] = !muted[index] },
                )
            }
        },
        bottomOverlay = {
            val text: String?
            val tone: DemoStatusTone
            var icon = Icons.Filled.Headphones
            when {
                // The SDK's own "AR unavailable" card carries reason and retry.
                arCoreAvailability != null -> {
                    text = null
                    tone = DemoStatusTone.Progress
                }
                stemsFailed -> {
                    text = stringResource(R.string.demo_ar_sound_garden_sounds_failed)
                    tone = DemoStatusTone.Blocked
                }
                anchor == null && trackingFailureReason != null -> {
                    text = stringResource(R.string.demo_ar_sound_garden_scan_lost)
                    tone = DemoStatusTone.Guidance
                }
                anchor == null && !headphones -> {
                    text = stringResource(R.string.demo_ar_sound_garden_headphones)
                    tone = DemoStatusTone.Guidance
                }
                anchor == null -> {
                    text = stringResource(R.string.demo_ar_sound_garden_scan)
                    tone = DemoStatusTone.Progress
                }
                else -> {
                    icon = Icons.Filled.GraphicEq
                    text = when (hint) {
                        HINT_WALK -> stringResource(R.string.demo_ar_sound_garden_hint_walk)
                        HINT_TURN -> stringResource(R.string.demo_ar_sound_garden_hint_turn)
                        else -> null
                    }
                    tone = DemoStatusTone.Progress
                }
            }
            DemoStatusBanner(text = text, tone = tone, icon = icon)
        },
        dock = listOf(
            DockItem(
                icon = Icons.Filled.Refresh,
                label = stringResource(R.string.demo_ar_sound_garden_replant),
                caption = stringResource(R.string.demo_ar_sound_garden_replant_caption),
                onClick = { replant() },
                enabled = anchor != null,
            ),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
                // The grid is a scanning aid; once the garden stands, it is clutter.
                planeRenderer = anchor == null,
                onARCoreAvailability = { arCoreAvailability = it },
                onTrackingFailureChanged = { reason -> trackingFailureReason = reason },
                onSessionUpdated = onSessionUpdated,
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { _, node ->
                        orbIndexOf(node)?.let { index -> muted[index] = !muted[index] }
                    },
                ),
            ) {
                val coreMaterials = ORBS.map { orb ->
                    key(orb.asset) { rememberUnlitMaterialInstance(materialLoader, orb.color) }
                }
                val haloMaterials = ORBS.map { orb ->
                    key(orb.asset) {
                        rememberUnlitMaterialInstance(materialLoader, orb.color.copy(alpha = HALO_ALPHA_MIN))
                    }
                }
                val shellMaterials = ORBS.map { orb ->
                    key(orb.asset) { rememberUnlitMaterialInstance(materialLoader, orb.color.copy(alpha = 0f)) }
                }
                val stalkMaterials = ORBS.map { orb ->
                    key(orb.asset) {
                        rememberUnlitMaterialInstance(materialLoader, orb.color.copy(alpha = STALK_ALPHA))
                    }
                }
                val drawn = visual
                SideEffect {
                    ORBS.forEachIndexed { i, orb ->
                        val pulse = drawn.pulse[i]
                        haloMaterials[i].setColor(
                            orb.color.copy(alpha = HALO_ALPHA_MIN + (HALO_ALPHA_MAX - HALO_ALPHA_MIN) * pulse),
                        )
                        shellMaterials[i].setColor(orb.color.copy(alpha = drawn.shellAlpha[i]))
                        coreMaterials[i].setColor(
                            if (muted[i]) orb.color.copy(alpha = MUTED_CORE_ALPHA) else orb.color,
                        )
                    }
                }
                anchor?.let { garden ->
                    key(garden) {
                        AnchorNode(anchor = garden) {
                            ORBS.forEachIndexed { i, orb ->
                                val grow = easeOutBack(drawn.bloom[i]).coerceAtLeast(MIN_SCALE)
                                val pulse = drawn.pulse[i]
                                val base = Position(orb.local.x, 0f, orb.local.z)
                                // The stalk and the ring on the floor tie each orb to the ground
                                // it was planted in — without them a floating sphere has no
                                // readable distance on a camera feed.
                                PathNode(
                                    points = remember(i) { ringPoints(base, RING_RADIUS) },
                                    closed = true,
                                    materialInstance = stalkMaterials[i],
                                )
                                PathNode(
                                    points = remember(i) { listOf(base, orb.local) },
                                    materialInstance = stalkMaterials[i],
                                )
                                // The sound leaving the orb: a shell per note, born at the core
                                // and fading as it grows — the eye sees each note the ear hears.
                                SphereNode(
                                    radius = CORE_RADIUS,
                                    materialInstance = shellMaterials[i],
                                    position = orb.local,
                                    scale = Scale((grow * drawn.shellScale[i]).coerceAtLeast(MIN_SCALE)),
                                    apply = { name = orbNodeName(i) },
                                )
                                SphereNode(
                                    radius = CORE_RADIUS,
                                    materialInstance = haloMaterials[i],
                                    position = orb.local,
                                    scale = Scale(grow * (HALO_SCALE_MIN + HALO_SCALE_PULSE * pulse)),
                                    apply = { name = orbNodeName(i) },
                                )
                                SphereNode(
                                    radius = CORE_RADIUS,
                                    materialInstance = coreMaterials[i],
                                    position = orb.local,
                                    scale = Scale(grow * (1f + CORE_SCALE_PULSE * pulse)),
                                    apply = { name = orbNodeName(i) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One orb: the part it plays, where it stands in the garden, the colour it glows in. */
private data class Orb(val asset: String, val nameRes: Int, val local: Float3, val color: Color)

/**
 * The garden, in the anchor's frame (metres; +X is the user's right and +Z points at the user
 * when planted). The bright, short parts stand in front at different heights; the long ones
 * behind — so the first step forward already changes the balance. Colours are palette values
 * only (`DESIGN.md`), one hue per part, chosen to stay apart on a camera feed.
 */
private val ORBS = listOf(
    Orb(
        "audio/garden_bells.ogg", R.string.demo_ar_sound_garden_part_bells,
        Float3(-0.7f, 1.0f, 0.45f), SceneViewColors.TintSoft,
    ),
    Orb(
        "audio/garden_beat.ogg", R.string.demo_ar_sound_garden_part_beat,
        Float3(0.7f, 0.35f, 0.45f), SceneViewTokens.ArOverlay.accentGuidance,
    ),
    Orb(
        "audio/garden_pad.ogg", R.string.demo_ar_sound_garden_part_pad,
        Float3(-0.55f, 1.35f, -0.65f), SceneViewColors.TintLight,
    ),
    Orb(
        "audio/garden_bass.ogg", R.string.demo_ar_sound_garden_part_bass,
        Float3(0.55f, 0.6f, -0.65f), SceneViewColors.Accent,
    ),
)

/** Per-frame values the scene draws, replaced as a whole each AR frame. */
private class GardenVisual(
    val bloom: FloatArray,
    val pulse: FloatArray,
    val shellScale: FloatArray,
    val shellAlpha: FloatArray,
) {
    companion object {
        val EMPTY = GardenVisual(
            FloatArray(ORBS.size), FloatArray(ORBS.size), FloatArray(ORBS.size), FloatArray(ORBS.size),
        )
    }
}

/** Frame-to-frame smoothing state — not Compose state, nothing draws from it directly. */
private class GardenMotion(count: Int) {
    val muteLevel = FloatArray(count) { 1f }
    var lastNanos = 0L
}

/**
 * Finds the floor and anchors the garden on it, [PLANT_DISTANCE_M] ahead of the user and
 * turned so its +Z faces them. The lowest tracked upward plane wins: the first plane ARCore
 * reports is often a table, and a garden wants the floor.
 */
private fun plant(session: Session, listener: ListenerFrame): Anchor? {
    val floor = session.getAllTrackables(Plane::class.java)
        .filter {
            it.trackingState == TrackingState.TRACKING &&
                it.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                it.subsumedBy == null
        }
        .minByOrNull { it.centerPose.ty() }
        ?: return null
    val f = listener.forward
    val centre = floatArrayOf(
        listener.position.x + f.x * PLANT_DISTANCE_M,
        floor.centerPose.ty(),
        listener.position.z + f.z * PLANT_DISTANCE_M,
    )
    // Rotation about +Y that takes +Z to the direction back towards the user (−forward).
    val yaw = atan2(-f.x, -f.z)
    val rotation = floatArrayOf(0f, sin(yaw / 2f), 0f, cos(yaw / 2f))
    return runCatching { floor.createAnchor(Pose(centre, rotation)) }
        .onFailure { Log.w(TAG, "Could not anchor the garden", it) }
        .getOrNull()
}

/** Pushes this frame's four voices to the mixer and returns what the scene should draw. */
private fun updateGarden(
    garden: Anchor,
    listener: ListenerFrame,
    plantedAtNanos: Long,
    muted: List<Boolean>,
    motion: GardenMotion,
    mix: SpatialMixCore?,
    envelopes: List<FloatArray>?,
    lastOnsets: List<IntArray>?,
    playedFrames: Long,
): GardenVisual {
    val now = System.nanoTime()
    val dt = if (motion.lastNanos == 0L) 0f else ((now - motion.lastNanos) / 1e9f).coerceIn(0f, 0.1f)
    motion.lastNanos = now
    val sincePlant = (now - plantedAtNanos) / 1e9f
    val pose = garden.pose
    val bloom = FloatArray(ORBS.size)
    val pulse = FloatArray(ORBS.size)
    val shellScale = FloatArray(ORBS.size)
    val shellAlpha = FloatArray(ORBS.size)
    ORBS.forEachIndexed { i, orb ->
        // The song builds up: one orb, then the next, BLOOM_STAGGER_S apart.
        bloom[i] = ((sincePlant - i * BLOOM_STAGGER_S) / BLOOM_S).coerceIn(0f, 1f)
        val muteTarget = if (muted[i]) 0f else 1f
        motion.muteLevel[i] += (muteTarget - motion.muteLevel[i]) * (dt / MUTE_FADE_S).coerceAtMost(1f)
        val level = bloom[i] * motion.muteLevel[i]
        val world = pose.transformPoint(floatArrayOf(orb.local.x, orb.local.y, orb.local.z))
        mix?.setTarget(i, SpatialVoiceMath.voice(listener, Float3(world[0], world[1], world[2]), FALLOFF, level))
        val envelope = envelopes?.get(i)
        pulse[i] = if (envelope == null) 0f else SoundGardenStems.envelopeAt(envelope, playedFrames) * level
        val age = lastOnsets?.get(i)?.let { SoundGardenStems.secondsSinceOnset(it, playedFrames) }
        val t = if (age == null) 1f else (age / SHELL_LIFE_S).coerceIn(0f, 1f)
        val fade = (1f - t) * (1f - t)
        // A spent shell shrinks to nothing: an invisible 0.4 m sphere would still catch taps.
        shellScale[i] = if (fade <= 0f) 0f else SHELL_SCALE_MIN + (SHELL_SCALE_MAX - SHELL_SCALE_MIN) * (1f - fade)
        shellAlpha[i] = SHELL_ALPHA_MAX * fade * level
    }
    return GardenVisual(bloom, pulse, shellScale, shellAlpha)
}

/** The four parts as toggles: who is who, and which ones are muted. Glass over the feed. */
@Composable
private fun GardenLegend(names: List<String>, muted: List<Boolean>, onToggle: (Int) -> Unit) {
    GlassSurface(modifier = Modifier.padding(horizontal = SceneViewTokens.Space.md)) {
        Row(
            modifier = Modifier.padding(horizontal = SceneViewTokens.Space.sm, vertical = SceneViewTokens.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ORBS.forEachIndexed { i, orb ->
                Row(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .toggleable(value = !muted[i], role = Role.Switch, onValueChange = { onToggle(i) })
                        .alpha(if (muted[i]) MUTED_CHIP_ALPHA else 1f)
                        .padding(horizontal = SceneViewTokens.Space.sm, vertical = SceneViewTokens.Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
                ) {
                    Box(Modifier.size(SWATCH_SIZE).background(orb.color, CircleShape))
                    Text(
                        text = names[i],
                        style = SceneViewTokens.Type.caption,
                        color = SceneViewTokens.Glass.onGlass,
                    )
                }
            }
        }
    }
}

/** True while a headset is the output — updated live as one is plugged or paired. */
@Composable
private fun rememberHeadphonesConnected(context: Context): Boolean {
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    var connected by remember { mutableStateOf(hasHeadphones(audioManager)) }
    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                connected = hasHeadphones(audioManager)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                connected = hasHeadphones(audioManager)
            }
        }
        audioManager?.registerAudioDeviceCallback(callback, null)
        onDispose { audioManager?.unregisterAudioDeviceCallback(callback) }
    }
    return connected
}

private fun hasHeadphones(audioManager: AudioManager?): Boolean =
    audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().any { it.type in HEADPHONE_TYPES }

private val HEADPHONE_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    // API 31. A compile-time constant, so reading it on an older device is only a number that
    // no device there reports — nothing to guard.
    @Suppress("InlinedApi") AudioDeviceInfo.TYPE_BLE_HEADSET,
)

private fun orbNodeName(index: Int) = "sound-garden-orb-$index"

/** The orb a tap landed on, from the hit node or any of its parents. */
private fun orbIndexOf(node: Node?): Int? {
    var current = node
    while (current != null) {
        val index = ORBS.indices.firstOrNull { current?.name == orbNodeName(it) }
        if (index != null) return index
        current = current.parent
    }
    return null
}

/** A closed circle of [radius] on the floor around [centre]. */
private fun ringPoints(centre: Position, radius: Float, segments: Int = 40): List<Position> =
    List(segments) { index ->
        val angle = 2f * PI.toFloat() * index / segments
        Position(centre.x + cos(angle) * radius, centre.y, centre.z + sin(angle) * radius)
    }

/** Overshoots a little before settling — a bloom, not a fade. */
private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val u = t - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

private const val TAG = "ARSoundGarden"

/** SDK inverse law: unity inside 0.5 m, −7 dB at 1 m, −14 dB at 2 m. */
private val FALLOFF = AudioFalloff.Inverse(refDistance = 0.5f, maxDistance = 8f, rolloffFactor = 1.3f)

private const val PLANT_DISTANCE_M = 1.6f
private const val BLOOM_STAGGER_S = 0.6f
private const val BLOOM_S = 0.7f
private const val MUTE_FADE_S = 0.12f

private const val CORE_RADIUS = 0.06f
private const val CORE_SCALE_PULSE = 0.18f
private const val HALO_SCALE_MIN = 1.7f
private const val HALO_SCALE_PULSE = 1.5f
private const val HALO_ALPHA_MIN = 0.16f
private const val HALO_ALPHA_MAX = 0.5f
private const val STALK_ALPHA = 0.55f

/** A note's shell grows from the core to ≈ 0.4 m radius and is gone in under a second. */
private const val SHELL_LIFE_S = 0.9f
private const val SHELL_SCALE_MIN = 1.2f
private const val SHELL_SCALE_MAX = 7f
private const val SHELL_ALPHA_MAX = 0.32f
private const val RING_RADIUS = 0.14f
private const val MUTED_CORE_ALPHA = 0.3f
private const val MIN_SCALE = 0.001f

private const val MUTED_CHIP_ALPHA = 0.45f
private val SWATCH_SIZE = 10.dp

private const val HINT_NONE = 0
private const val HINT_WALK = 1
private const val HINT_TURN = 2
private const val HINT_DURATION_MS = 8_000L
