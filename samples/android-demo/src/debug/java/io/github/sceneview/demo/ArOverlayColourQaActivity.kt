package io.github.sceneview.demo

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Half
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import io.github.sceneview.SceneView
import io.github.sceneview.demo.demos.MeshClassificationLegend
import io.github.sceneview.demo.demos.internal.rememberArMeshTintInstance
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.rememberARView
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberMaterialInstance
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Debug-only visual QA of the AR mesh overlay colours (#4623), no ARCore session needed — the
 * arm64 emulator has no camera AR can start on (#2754).
 *
 * Replays the AR colour chain: a room photo goes through what the camera-stream shader does to
 * the feed (exact sRGB EOTF, then `Inverse_Tonemap_Filmic`) into an HDR texture drawn behind
 * the scene, and the View is [rememberARView]'s, Filmic tone mapper included — so an overlay
 * blends with the photo exactly as it blends with the camera image. `variant before`: the
 * materials the Scene Geometry and Plane Node demos used before (55 %-alpha lit brand blue and
 * lilac, an amber cube); the default: `ar_mesh_tint` with the `ar-mesh-*` tokens.
 *
 * The lighting of the old lit materials is SceneView's default environment, not ARCore's light
 * estimate: approximate. The new material is unlit, so its blend with the photo is the AR one.
 *
 * ```
 * adb shell am start -n io.github.sceneview.demo.qa/io.github.sceneview.demo.ArOverlayColourQaActivity \
 *     --ei photo 1|2|3 [--es variant before]
 * ```
 */
class ArOverlayColourQaActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val photo = intent.getIntExtra(EXTRA_PHOTO, 1)
        val before = intent.getStringExtra(EXTRA_VARIANT) == VARIANT_BEFORE
        setContent {
            SceneViewDemoTheme {
                val engine = rememberEngine()
                val materialLoader = rememberMaterialLoader(engine)
                val backdrop = rememberCameraReplayBackdrop(engine, materialLoader, photo)
                val materials = if (before) {
                    BlockMaterials(
                        ground = rememberMaterialInstance(
                            materialLoader,
                            SceneViewColors.Primary.copy(alpha = OLD_ALPHA),
                            metallic = 0f, roughness = 1f, reflectance = 0f,
                        ),
                        structure = rememberMaterialInstance(
                            materialLoader,
                            SceneViewColors.TintSoft.copy(alpha = OLD_ALPHA),
                            metallic = 0f, roughness = 1f, reflectance = 0f,
                        ),
                        marker = remember(materialLoader) { materialLoader.createColorInstance(OLD_MARKER) },
                    )
                } else {
                    BlockMaterials(
                        ground = rememberArMeshTintInstance(materialLoader, SceneViewTokens.ArOverlay.meshGround),
                        structure = rememberArMeshTintInstance(materialLoader, SceneViewTokens.ArOverlay.meshStructure),
                        marker = rememberArMeshTintInstance(materialLoader, SceneViewTokens.ArOverlay.meshGround),
                    )
                }
                val label = if (before) "Before — 55 % alpha, lit" else "After — ar_mesh_tint"
                ReplayScene(engine, materialLoader, backdrop, materials, label)
            }
        }
    }

    private companion object {
        const val EXTRA_PHOTO = "photo"
        const val EXTRA_VARIANT = "variant"
        const val VARIANT_BEFORE = "before"
        const val OLD_ALPHA = 0.55f
        val OLD_MARKER = Color(0xFF_FF_B3_00)
    }
}

private class BlockMaterials(
    val ground: MaterialInstance,
    val structure: MaterialInstance,
    val marker: MaterialInstance,
)

@Composable
private fun ReplayScene(
    engine: Engine,
    materialLoader: MaterialLoader,
    backdrop: MaterialInstance,
    materials: BlockMaterials,
    label: String,
) {
    Box(Modifier.fillMaxSize()) {
        val camera = rememberCameraNode(engine) {
            position = Position(0f, 0f, 0f)
            focalLength = FOCAL_LENGTH_MM
        }
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = rememberModelLoader(engine),
            materialLoader = materialLoader,
            view = rememberARView(engine),
            cameraNode = camera,
            cameraManipulator = null,
            onGestureListener = null,
            autoCenterContent = false,
        ) {
            // The camera image: far behind everything, a little taller than the lens sees so it
            // fills the screen edge to edge and its sides crop as a camera preview's would.
            PlaneNode(
                size = Size(VIEW_HEIGHT * BACKDROP_OVERSCAN * PHOTO_ASPECT, VIEW_HEIGHT * BACKDROP_OVERSCAN),
                normal = Direction(z = 1f),
                materialInstance = backdrop,
                position = Position(0f, 0f, -BACKDROP_DISTANCE),
            )
            // A street block: ground slab over the floor, buildings rising into the wall.
            CubeNode(Size(2.4f, 0.02f, 3.0f), materialInstance = materials.ground, position = Position(0f, -1.1f, -3.4f))
            CubeNode(Size(0.5f, 1.9f, 0.5f), materialInstance = materials.structure, position = Position(-0.75f, -0.15f, -3.6f))
            CubeNode(Size(0.45f, 0.9f, 0.45f), materialInstance = materials.structure, position = Position(0.1f, -0.65f, -2.7f))
            CubeNode(Size(0.5f, 2.3f, 0.5f), materialInstance = materials.structure, position = Position(0.85f, 0.05f, -4.2f))
            CubeNode(Size(0.12f, 0.12f, 0.12f), materialInstance = materials.marker, position = Position(-0.35f, -1.03f, -2.4f))
        }
        Column(
            Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(SceneViewTokens.Space.md),
        ) {
            Text(
                text = label,
                style = SceneViewTokens.Type.caption,
                color = SceneViewTokens.ArOverlay.onScrim,
                modifier = Modifier
                    .background(SceneViewTokens.ArOverlay.scrimDark, RoundedCornerShape(SceneViewTokens.Radius.md))
                    .padding(horizontal = SceneViewTokens.Space.md, vertical = SceneViewTokens.Space.xs),
            )
        }
        MeshClassificationLegend(
            Modifier.align(Alignment.BottomStart).padding(SceneViewTokens.Space.md),
        )
    }
}

/**
 * The photo as the AR camera stream lands in the HDR buffer: exact sRGB decode, then
 * `Inverse_Tonemap_Filmic` (as `camera_stream_flat.mat` does per texel), in a half-float texture
 * the stock image material draws unchanged. Filmic then maps it back to the photo on screen.
 * The texture lives as long as the engine — a debug screen, freed with it.
 */
// The ShortBuffer takes the raw half-float bits that RGBA16F expects, not widened values.
@SuppressLint("HalfFloat")
@Composable
private fun rememberCameraReplayBackdrop(engine: Engine, materialLoader: MaterialLoader, photo: Int): MaterialInstance {
    val context = LocalContext.current
    return remember(engine, materialLoader, photo) {
        val resId = context.resources.getIdentifier("qa_backdrop_$photo", "drawable", context.packageName)
        val bitmap = BitmapFactory.decodeResource(context.resources, resId, BitmapFactory.Options().apply { inScaled = false })
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val buffer = ByteBuffer.allocateDirect(w * h * 4 * 2).order(ByteOrder.nativeOrder())
        val shorts = buffer.asShortBuffer()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                shorts.put(Half.toHalf(cameraToBuffer((p shr 16 and 0xFF) / 255f)))
                shorts.put(Half.toHalf(cameraToBuffer((p shr 8 and 0xFF) / 255f)))
                shorts.put(Half.toHalf(cameraToBuffer((p and 0xFF) / 255f)))
                shorts.put(Half.toHalf(1f))
            }
        }
        val texture = Texture.Builder()
            .width(w).height(h).levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA16F)
            .build(engine)
        texture.setImage(engine, 0, Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.HALF))
        materialLoader.createImageInstance(texture)
    }
}

/** `Inverse_Tonemap_Filmic(srgbToLinearExact(c))`, the camera shader's two legs, on one channel. */
internal fun cameraToBuffer(srgb: Float): Float {
    val s = srgb.coerceIn(0f, 1f)
    val x = if (s < SRGB_KNEE) s / SRGB_LINEAR_SLOPE else ((s + SRGB_OFFSET) / (1f + SRGB_OFFSET)).pow(SRGB_GAMMA)
    return (0.03f - 0.59f * x - sqrt(0.0009f + 1.3702f * x - 1.0127f * x * x)) / (-5.02f + 4.86f * x)
}

private const val SRGB_KNEE = 0.04045f
private const val SRGB_LINEAR_SLOPE = 12.92f
private const val SRGB_OFFSET = 0.055f
private const val SRGB_GAMMA = 2.4f

/** About 67° of vertical view: a phone's main camera held upright. */
private const val FOCAL_LENGTH_MM = 18.0
private const val BACKDROP_DISTANCE = 8f

/** The lens's view height at [BACKDROP_DISTANCE]: 2·d·tan(fov/2), tan = 12 mm / [FOCAL_LENGTH_MM]. */
private const val VIEW_HEIGHT = 2f * BACKDROP_DISTANCE * 12f / 18f

/** Covers the screen's taller-than-16:9 aspect and crops the photos' letterbox bars. */
private const val BACKDROP_OVERSCAN = 1.3f

/** The QA backdrop photos are 608 × 1080. */
private const val PHOTO_ASPECT = 608f / 1080f
