package io.github.sceneview.demo.demos

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.rememberMaterialInstance

/**
 * One swappable PBR material "set" — a named appearance the demo can apply
 * to the model at runtime.
 *
 * Each variant bundles the four parameters that fully describe a Filament
 * colored PBR surface: a base [color] plus the [metallic] / [roughness] /
 * [reflectance] triple. Swapping the active variant is the teaching point
 * of [TextureStreamingDemo] — it shows that material/texture data can be
 * exchanged on an *already-loaded* model without rebuilding its geometry.
 *
 * The variants are bundled in-app rather than fetched from the network so
 * the demo renders something useful with zero connectivity. Streaming the
 * same kind of data from a remote catalogue (Sketchfab material packs,
 * a CDN of `.ktx` texture sets, …) is a documented follow-up — see the
 * `changelog.d/1480-texture-streaming.md` note.
 */
private data class MaterialVariant(
    val labelRes: Int,
    val color: Color,
    val metallic: Float,
    val roughness: Float,
    val reflectance: Float,
)

/**
 * Runtime texture / material streaming sample (issue #1480).
 *
 * A single loaded model — a [SphereNode], the cleanest possible canvas for
 * reading a material — whose surface material is swapped live from a chip
 * picker. Selecting a chip reassigns the node's [com.google.android.filament.MaterialInstance]
 * via Filament's `setMaterialInstanceAt(...)`, which is cheap: no geometry
 * rebuild, no model reload. The model loads exactly once; only the material
 * data changes.
 *
 * Distinct from [MaterialsDemo] (#1423), which streams a *whole new model*
 * per chip. Here the model is constant and the material is the variable —
 * the pattern you reach for when an end user is "trying on" finishes,
 * skins, or texture packs on a product in a viewer.
 *
 * Threading: every [MaterialInstance] is allocated by
 * [rememberMaterialInstance] (a `DisposableEffect`-backed composable helper),
 * so all Filament JNI allocation happens on the main thread and every handle
 * is destroyed when the demo leaves the composition — no leak across a
 * home → demo → home navigation cycle. The runtime swap itself
 * (`setMaterialInstanceAt`) is driven from `SphereNode`'s `SideEffect`,
 * also on the main thread.
 *
 * Lighting uses the studio HDR so the metallic / roughness contrast across
 * the variants actually reads — PBR surfaces are heavily IBL-dependent and
 * look flat under default ambient light.
 */
@Composable
fun TextureStreamingDemo(onBack: () -> Unit) {
    // Bundled material "sets" — a spread of metallic / roughness so the
    // runtime swap is visually obvious. Polished Steel and Brushed Gold sit
    // at high metallic; Matte Plastic and Glazed Ceramic at the dielectric
    // end; Copper bridges the two with a warm tint.
    val variants = remember {
        listOf(
            MaterialVariant(
                labelRes = R.string.demo_texture_streaming_variant_steel,
                color = Color(0xFFB8BCC4),
                metallic = 1.0f,
                roughness = 0.18f,
                reflectance = 0.6f,
            ),
            MaterialVariant(
                labelRes = R.string.demo_texture_streaming_variant_gold,
                color = Color(0xFFE6B64C),
                metallic = 1.0f,
                roughness = 0.32f,
                reflectance = 0.7f,
            ),
            MaterialVariant(
                labelRes = R.string.demo_texture_streaming_variant_copper,
                color = Color(0xFFC06A3E),
                metallic = 0.85f,
                roughness = 0.45f,
                reflectance = 0.6f,
            ),
            MaterialVariant(
                labelRes = R.string.demo_texture_streaming_variant_plastic,
                color = Color(0xFF6446CD),
                metallic = 0.0f,
                roughness = 0.7f,
                reflectance = 0.4f,
            ),
            MaterialVariant(
                labelRes = R.string.demo_texture_streaming_variant_ceramic,
                color = Color(0xFFEDE8E0),
                metallic = 0.0f,
                roughness = 0.12f,
                reflectance = 0.5f,
            ),
        )
    }

    var selectedIndex by remember { mutableIntStateOf(0) }
    val selectedVariant = variants[selectedIndex]

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Studio HDR + skybox — metallic variants read flat without an
    // environment to reflect. Falls back to the neutral IBL while the HDR
    // streams in (rememberHDREnvironment returns null until decoded).
    val hdrEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_2k.hdr",
        createSkybox = true,
    )
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    val activeEnvironment = hdrEnvironment ?: fallbackEnvironment

    // One MaterialInstance per variant, allocated up front and owned by the
    // composition. Pre-allocating all of them (instead of one re-keyed
    // instance) means a chip tap is a pure pointer swap — no JNI allocation
    // on the interaction path, so the swap is instant.
    val materialInstances = variants.map { variant ->
        rememberMaterialInstance(
            materialLoader = materialLoader,
            color = variant.color,
            metallic = variant.metallic,
            roughness = variant.roughness,
            reflectance = variant.reflectance,
        )
    }
    val selectedMaterial = materialInstances[selectedIndex]

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_texture_streaming_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            Text(
                text = stringResource(R.string.demo_texture_streaming_picker_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                variants.forEachIndexed { index, variant ->
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        label = { Text(stringResource(variant.labelRes)) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.demo_texture_streaming_variant_detail,
                    selectedVariant.metallic,
                    selectedVariant.roughness,
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        },
    ) {
        // The model is a primitive SphereNode (built synchronously, no async
        // load), so the idle orbit can start right away.
        val cameraManipulator = rememberHeroOrbitCameraManipulator(
            trigger = true,
            radius = 1.6f,
            yHeight = 0f,
            durationMillis = 20_000,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = activeEnvironment,
                cameraManipulator = cameraManipulator,
            ) {
                // The model is loaded once. `SphereNode` propagates a changed
                // `materialInstance` through `setMaterialInstanceAt(0, …)` in
                // its SideEffect, so swapping the picker re-skins this exact
                // node without rebuilding geometry — the streaming-swap point.
                SphereNode(
                    radius = 0.5f,
                    materialInstance = selectedMaterial,
                    position = Position(0f, 0f, 0f),
                )
            }
        }
    }
}
