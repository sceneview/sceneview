// ─── Character viewer code generator ──────────────────────────────────────────

export type CharacterStyle =
  | "humanoid"
  | "cartoon"
  | "chibi"
  | "robot"
  | "creature"
  | "animal"
  | "fantasy"
  | "sci-fi";

export type AnimationState =
  | "idle"
  | "walk"
  | "run"
  | "jump"
  | "attack"
  | "die"
  | "dance"
  | "wave";

export const CHARACTER_STYLES: CharacterStyle[] = [
  "humanoid", "cartoon", "chibi", "robot", "creature", "animal", "fantasy", "sci-fi",
];

export const ANIMATION_STATES: AnimationState[] = [
  "idle", "walk", "run", "jump", "attack", "die", "dance", "wave",
];

export interface CharacterViewerOptions {
  style: CharacterStyle;
  animations?: AnimationState[];
  autoRotate?: boolean;
  showControls?: boolean;
  ar?: boolean;
}

/**
 * Generates a complete, compilable Kotlin composable for a 3D character viewer
 * with animation state management.
 */
export function generateCharacterViewer(options: CharacterViewerOptions): string {
  const {
    style,
    animations = ["idle", "walk", "run"],
    autoRotate = false,
    showControls = true,
    ar = false,
  } = options;

  const modelPath = getCharacterModelPath(style);
  const composableName = `${capitalize(style)}CharacterViewer`;

  if (ar) {
    return generateArCharacterViewer(composableName, modelPath, options);
  }

  const animationButtons = animations
    .map(
      (anim) =>
        `                Button(
                    onClick = { currentAnimation = "${anim}" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentAnimation == "${anim}")
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text("${capitalize(anim)}")
                }`
    )
    .join("\n");

  return `package com.example.gaming.character

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.node.LightNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation

/**
 * ${capitalize(style)} character viewer with animation controls.
 *
 * Displays a 3D ${style} character model with switchable animation states
 * (${animations.join(", ")}), orbit camera, and pinch-to-zoom.
 *
 * Model: Place your animated GLB file at src/main/assets/${modelPath}
 * The GLB must contain named animations matching the state names.
 * Free models: Mixamo (mixamo.com), Kenney (kenney.nl), Quaternius.
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)

    val modelInstance = rememberModelInstance(modelLoader, "${modelPath}")

    var currentAnimation by remember { mutableStateOf("${animations[0]}") }
${autoRotate ? `    var rotationY by remember { mutableFloatStateOf(0f) }` : ""}

    // Apply animation when state changes
    LaunchedEffect(currentAnimation, modelInstance) {
        modelInstance?.let { instance ->
            val animator = instance.animator
            // Find animation index by name
            for (i in 0 until animator.animationCount) {
                val name = animator.getAnimationName(i)
                if (name.contains(currentAnimation, ignoreCase = true)) {
                    animator.applyAnimation(i, 0f)
                    break
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 3D Scene ──────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                collisionSystem = collisionSystem,
                environment = environmentLoader.createHDREnvironment(
                    assetFileLocation = "environments/studio_hdr.ktx"
                )!!,
                onFrame = { frameTimeNanos ->
                    modelInstance?.let { instance ->
                        val animator = instance.animator
                        // Advance animation each frame
                        val deltaSeconds = frameTimeNanos / 1_000_000_000f
                        if (animator.animationCount > 0) {
                            animator.updateBoneMatrices()
                        }
                    }
${autoRotate ? `                    rotationY += 0.5f` : ""}
                }
            ) {
                // Character model
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 1.5f${autoRotate ? `,
                        rotation = Rotation(y = rotationY)` : ""}
                    )
                }

                // Three-point lighting for character presentation
                LightNode(
                    apply = {
                        intensity(100_000f)
                        color(1.0f, 0.95f, 0.9f)
                        direction(0.5f, -1f, -0.5f)
                    }
                )

                LightNode(
                    apply = {
                        intensity(40_000f)
                        color(0.85f, 0.9f, 1.0f)
                        direction(-0.5f, -0.3f, 0.5f)
                    }
                )

                LightNode(
                    apply = {
                        intensity(20_000f)
                        color(1.0f, 1.0f, 1.0f)
                        direction(0f, 0.2f, 1f)
                    }
                )
            }

            // Loading indicator
            if (modelInstance == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

${showControls ? `        // ── Animation Controls ──────────────────────────────────────────
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Animation: \${currentAnimation.replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
${animationButtons}
                }
            }
        }` : ""}
    }
}`;
}

function generateArCharacterViewer(
  composableName: string,
  modelPath: string,
  options: CharacterViewerOptions
): string {
  const animations = options.animations ?? ["idle", "walk", "run"];
  return `package com.example.gaming.character

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.math.Position

/**
 * AR ${options.style} character viewer.
 *
 * Places an animated character in augmented reality. Tap a surface to place,
 * then use controls to switch animations (${animations.join(", ")}).
 *
 * Model: Place your animated GLB at src/main/assets/${modelPath}
 *
 * Required AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.CAMERA" />
 *   <uses-feature android:name="android.hardware.camera.ar" android:required="true" />
 *   <meta-data android:name="com.google.ar.core" android:value="required" />
 *
 * Gradle: implementation("io.github.sceneview:arsceneview:3.6.0")
 */
@Composable
fun ${composableName}AR() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)

    val modelInstance = rememberModelInstance(modelLoader, "${modelPath}")

    var placed by remember { mutableStateOf(false) }
    var currentAnimation by remember { mutableStateOf("${animations[0]}") }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            collisionSystem = collisionSystem,
            planeRenderer = true,
            onTapAR = { hitResult ->
                if (!placed && modelInstance != null) {
                    val anchor = hitResult.createAnchor()
                    placed = true
                }
            }
        ) {
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 0.5f
                )
            }
        }

        // Placement instruction
        if (!placed) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Point camera at a flat surface, then tap to place character",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Animation selector
        if (placed) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            ) {
${animations
  .map(
    (anim) =>
      `                Button(onClick = { currentAnimation = "${anim}" }) {
                    Text("${capitalize(anim)}")
                }`
  )
  .join("\n")}
            }
        }
    }
}`;
}

function getCharacterModelPath(style: CharacterStyle): string {
  return `models/characters/${style}_character.glb`;
}

function capitalize(s: string): string {
  return s
    .split("-")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join("");
}
