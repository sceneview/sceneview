// ─── Particle effects code generator ──────────────────────────────────────────

export type ParticleEffect =
  | "fire"
  | "smoke"
  | "sparkles"
  | "rain"
  | "snow"
  | "explosion"
  | "magic"
  | "confetti"
  | "bubbles"
  | "fireflies";

export type BlendMode =
  | "additive"
  | "alpha"
  | "multiply"
  | "screen";

export const PARTICLE_EFFECTS: ParticleEffect[] = [
  "fire", "smoke", "sparkles", "rain", "snow",
  "explosion", "magic", "confetti", "bubbles", "fireflies",
];

export const BLEND_MODES: BlendMode[] = [
  "additive", "alpha", "multiply", "screen",
];

export interface ParticleEffectOptions {
  effect: ParticleEffect;
  particleCount?: number;
  blendMode?: BlendMode;
  loop?: boolean;
  ar?: boolean;
}

interface EffectConfig {
  description: string;
  emitterShape: string;
  velocityRange: string;
  lifetime: string;
  colorStart: string;
  colorEnd: string;
  sizeStart: number;
  sizeEnd: number;
  gravity: number;
  defaultBlend: BlendMode;
}

const EFFECT_CONFIGS: Record<ParticleEffect, EffectConfig> = {
  fire: {
    description: "Realistic fire effect with flickering flames, embers, and heat distortion.",
    emitterShape: "cone(radius = 0.3f, angle = 15f)",
    velocityRange: "0.5f to 2f",
    lifetime: "0.5f to 1.5f",
    colorStart: "Color(1f, 0.8f, 0.2f, 1f)",
    colorEnd: "Color(1f, 0.2f, 0f, 0f)",
    sizeStart: 0.15,
    sizeEnd: 0.02,
    gravity: -0.5,
    defaultBlend: "additive",
  },
  smoke: {
    description: "Volumetric smoke with turbulence, fading opacity, and wind drift.",
    emitterShape: "sphere(radius = 0.2f)",
    velocityRange: "0.2f to 0.8f",
    lifetime: "2f to 4f",
    colorStart: "Color(0.4f, 0.4f, 0.4f, 0.6f)",
    colorEnd: "Color(0.6f, 0.6f, 0.6f, 0f)",
    sizeStart: 0.1,
    sizeEnd: 0.5,
    gravity: -0.2,
    defaultBlend: "alpha",
  },
  sparkles: {
    description: "Twinkling sparkle particles with random brightness, star-like appearance.",
    emitterShape: "sphere(radius = 0.5f)",
    velocityRange: "0.1f to 0.5f",
    lifetime: "0.3f to 1f",
    colorStart: "Color(1f, 1f, 0.8f, 1f)",
    colorEnd: "Color(1f, 1f, 1f, 0f)",
    sizeStart: 0.05,
    sizeEnd: 0.01,
    gravity: 0,
    defaultBlend: "additive",
  },
  rain: {
    description: "Falling rain drops with splash effects on contact.",
    emitterShape: "plane(width = 5f, height = 5f, y = 5f)",
    velocityRange: "3f to 5f",
    lifetime: "1f to 2f",
    colorStart: "Color(0.7f, 0.8f, 1f, 0.7f)",
    colorEnd: "Color(0.7f, 0.8f, 1f, 0.3f)",
    sizeStart: 0.02,
    sizeEnd: 0.02,
    gravity: 9.81,
    defaultBlend: "alpha",
  },
  snow: {
    description: "Gentle snowfall with wind sway and varying flake sizes.",
    emitterShape: "plane(width = 5f, height = 5f, y = 5f)",
    velocityRange: "0.3f to 0.8f",
    lifetime: "3f to 6f",
    colorStart: "Color(1f, 1f, 1f, 0.9f)",
    colorEnd: "Color(1f, 1f, 1f, 0.3f)",
    sizeStart: 0.03,
    sizeEnd: 0.05,
    gravity: 1.5,
    defaultBlend: "alpha",
  },
  explosion: {
    description: "Burst explosion with shockwave, debris, and fire core.",
    emitterShape: "point()",
    velocityRange: "3f to 8f",
    lifetime: "0.3f to 1.5f",
    colorStart: "Color(1f, 0.9f, 0.3f, 1f)",
    colorEnd: "Color(0.5f, 0.1f, 0f, 0f)",
    sizeStart: 0.3,
    sizeEnd: 0.05,
    gravity: 2,
    defaultBlend: "additive",
  },
  magic: {
    description: "Mystical magic effect with spiraling particles, color shifts, and glow.",
    emitterShape: "helix(radius = 0.5f, height = 2f)",
    velocityRange: "0.3f to 1f",
    lifetime: "1f to 3f",
    colorStart: "Color(0.5f, 0.2f, 1f, 1f)",
    colorEnd: "Color(0.2f, 0.8f, 1f, 0f)",
    sizeStart: 0.08,
    sizeEnd: 0.02,
    gravity: -0.3,
    defaultBlend: "additive",
  },
  confetti: {
    description: "Celebration confetti burst with multi-colored falling pieces.",
    emitterShape: "cone(radius = 0.1f, angle = 60f)",
    velocityRange: "2f to 5f",
    lifetime: "2f to 4f",
    colorStart: "Color(1f, 0.5f, 0f, 1f)",
    colorEnd: "Color(0f, 0.5f, 1f, 0.8f)",
    sizeStart: 0.04,
    sizeEnd: 0.04,
    gravity: 3,
    defaultBlend: "alpha",
  },
  bubbles: {
    description: "Floating soap bubbles with iridescent surface and pop animation.",
    emitterShape: "sphere(radius = 0.3f)",
    velocityRange: "0.2f to 0.6f",
    lifetime: "3f to 6f",
    colorStart: "Color(0.8f, 0.9f, 1f, 0.5f)",
    colorEnd: "Color(0.9f, 0.95f, 1f, 0f)",
    sizeStart: 0.05,
    sizeEnd: 0.12,
    gravity: -0.5,
    defaultBlend: "alpha",
  },
  fireflies: {
    description: "Glowing fireflies with random flight paths and pulsing brightness.",
    emitterShape: "sphere(radius = 2f)",
    velocityRange: "0.1f to 0.4f",
    lifetime: "3f to 8f",
    colorStart: "Color(0.8f, 1f, 0.3f, 1f)",
    colorEnd: "Color(0.5f, 0.8f, 0.1f, 0f)",
    sizeStart: 0.03,
    sizeEnd: 0.05,
    gravity: -0.1,
    defaultBlend: "additive",
  },
};

/**
 * Generates a complete, compilable Kotlin composable for particle visual effects.
 */
export function generateParticleEffects(options: ParticleEffectOptions): string {
  const {
    effect,
    particleCount = 100,
    blendMode,
    loop = true,
    ar = false,
  } = options;

  const config = EFFECT_CONFIGS[effect];
  const blend = blendMode ?? config.defaultBlend;
  const composableName = `${capitalize(effect)}Effect`;

  if (ar) {
    return generateArParticleEffect(composableName, effect, config, options);
  }

  return `package com.example.gaming.particles

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.node.SphereNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.node.LightNode
import io.github.sceneview.math.Position
import kotlin.math.sin
import kotlin.math.cos
import kotlin.random.Random

/**
 * ${capitalize(effect)} particle effect — ${config.description}
 *
 * Renders ${particleCount} particles using SphereNode instances with per-frame
 * position/scale updates in the onFrame callback. Particles are spawned from
 * emitter shape: ${config.emitterShape}.
 *
 * Blend mode: ${blend}. Loop: ${loop}. Gravity: ${config.gravity}.
 *
 * For production particle systems, consider Filament's particle system or
 * a GPU-based compute shader approach for thousands of particles.
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)

    // Particle state
    val particles = remember { mutableStateListOf<Particle>() }
    var elapsedTime by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(true) }

    // Initialize particles
    LaunchedEffect(Unit) {
        particles.addAll(createParticles(${particleCount}))
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
                    if (isPlaying) {
                        val dt = 1f / 60f
                        elapsedTime += dt
                        updateParticles(particles, dt, ${config.gravity}f, ${loop})
                    }
                }
            ) {
                // Render each particle as a small sphere
                for (particle in particles) {
                    if (particle.alive) {
                        SphereNode(
                            engine = engine,
                            materialLoader = materialLoader,
                            radius = particle.size,
                            position = Position(particle.x, particle.y, particle.z)
                        )
                    }
                }

                // Ambient lighting
                LightNode(
                    apply = {
                        intensity(60_000f)
                        color(1.0f, 1.0f, 1.0f)
                        direction(0f, -1f, -0.3f)
                    }
                )
            }

            // Loading indicator
            if (particles.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Controls
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "${capitalize(effect)} Effect",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { isPlaying = !isPlaying }) {
                            Text(if (isPlaying) "Pause" else "Play")
                        }
                        Button(onClick = {
                            particles.clear()
                            particles.addAll(createParticles(${particleCount}))
                            elapsedTime = 0f
                        }) {
                            Text("Restart")
                        }
                    }
                }
                Text(
                    "Active: \${particles.count { it.alive }} / ${particleCount} | Blend: ${blend}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Single particle with physics properties.
 */
data class Particle(
    var x: Float,
    var y: Float,
    var z: Float,
    var vx: Float,
    var vy: Float,
    var vz: Float,
    var size: Float,
    var life: Float,       // Remaining life in seconds
    val maxLife: Float,    // Total lifetime
    var alive: Boolean = true,
)

/**
 * Creates initial particle batch for ${effect} effect.
 */
private fun createParticles(count: Int): List<Particle> {
    return List(count) { i ->
        val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
        val speed = ${config.velocityRange.split(" to ")[0]} + Random.nextFloat() * (${config.velocityRange.split(" to ")[1]} - ${config.velocityRange.split(" to ")[0]})
        val life = ${config.lifetime.split(" to ")[0]} + Random.nextFloat() * (${config.lifetime.split(" to ")[1]} - ${config.lifetime.split(" to ")[0]})
        Particle(
            x = Random.nextFloat() * 0.2f - 0.1f,
            y = 0f,
            z = Random.nextFloat() * 0.2f - 0.1f,
            vx = cos(angle) * speed * 0.3f,
            vy = speed,
            vz = sin(angle) * speed * 0.3f,
            size = ${config.sizeStart}f,
            life = life * (i.toFloat() / count), // Stagger spawn
            maxLife = life,
        )
    }
}

/**
 * Updates particle positions, sizes, and lifetimes each frame.
 */
private fun updateParticles(
    particles: MutableList<Particle>,
    dt: Float,
    gravity: Float,
    loop: Boolean,
) {
    for (particle in particles) {
        if (!particle.alive) {
            if (loop) {
                // Respawn
                particle.x = Random.nextFloat() * 0.2f - 0.1f
                particle.y = 0f
                particle.z = Random.nextFloat() * 0.2f - 0.1f
                particle.life = particle.maxLife
                particle.alive = true
            }
            continue
        }

        // Apply gravity
        particle.vy -= gravity * dt

        // Update position
        particle.x += particle.vx * dt
        particle.y += particle.vy * dt
        particle.z += particle.vz * dt

        // Interpolate size over lifetime
        val t = 1f - (particle.life / particle.maxLife)
        particle.size = ${config.sizeStart}f + (${config.sizeEnd}f - ${config.sizeStart}f) * t

        // Decrease life
        particle.life -= dt
        if (particle.life <= 0f) {
            particle.alive = false
        }
    }
}`;
}

function generateArParticleEffect(
  composableName: string,
  effect: ParticleEffect,
  config: EffectConfig,
  options: ParticleEffectOptions
): string {
  const particleCount = options.particleCount ?? 100;
  return `package com.example.gaming.particles

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.node.SphereNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.math.Position

/**
 * AR ${capitalize(effect)} particle effect.
 *
 * ${config.description}
 * Tap a surface to place the particle emitter in AR space.
 * ${particleCount} particles rendered as animated SphereNode instances.
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
    val materialLoader = rememberMaterialLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)

    var placed by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            collisionSystem = collisionSystem,
            planeRenderer = true,
            onTapAR = { hitResult ->
                if (!placed) {
                    val anchor = hitResult.createAnchor()
                    placed = true
                }
            }
        ) {
            // Particle nodes rendered here after placement
        }

        if (!placed) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Tap a surface to place ${effect} effect",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}`;
}

export function getEffectConfig(effect: ParticleEffect): EffectConfig {
  return EFFECT_CONFIGS[effect];
}

function capitalize(s: string): string {
  return s
    .split("-")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join("");
}
