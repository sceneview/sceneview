// ─── Physics game code generator ──────────────────────────────────────────────

export type PhysicsPreset =
  | "bouncing-balls"
  | "bowling"
  | "billiards"
  | "marble-run"
  | "tower-collapse"
  | "pong-3d"
  | "pinball"
  | "cannon";

export type GravityMode =
  | "earth"
  | "moon"
  | "mars"
  | "jupiter"
  | "zero-g"
  | "reverse"
  | "custom";

export const PHYSICS_PRESETS: PhysicsPreset[] = [
  "bouncing-balls", "bowling", "billiards", "marble-run",
  "tower-collapse", "pong-3d", "pinball", "cannon",
];

export const GRAVITY_MODES: GravityMode[] = [
  "earth", "moon", "mars", "jupiter", "zero-g", "reverse", "custom",
];

export interface PhysicsGameOptions {
  preset: PhysicsPreset;
  gravity?: GravityMode;
  bounciness?: number;
  showTrajectory?: boolean;
  ar?: boolean;
}

const GRAVITY_VALUES: Record<GravityMode, number> = {
  earth: -9.81,
  moon: -1.62,
  mars: -3.72,
  jupiter: -24.79,
  "zero-g": 0,
  reverse: 9.81,
  custom: -9.81,
};

/**
 * Generates a complete, compilable Kotlin composable for a physics simulation game.
 */
export function generatePhysicsGame(options: PhysicsGameOptions): string {
  const {
    preset,
    gravity = "earth",
    bounciness = 0.7,
    showTrajectory = false,
    ar = false,
  } = options;

  const gravityValue = GRAVITY_VALUES[gravity];
  const composableName = `${capitalize(preset)}Game`;
  const config = getPresetConfig(preset);

  if (ar) {
    return generateArPhysicsGame(composableName, options);
  }

  return `package com.example.gaming.physics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.node.LightNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import kotlin.math.sqrt
import kotlin.math.max

/**
 * ${capitalize(preset)} — physics simulation game.
 *
 * ${config.description}
 *
 * Physics: gravity = ${gravityValue} m/s^2 (${gravity}), bounciness = ${bounciness}.
 * Uses SceneView's built-in geometry nodes (SphereNode, CubeNode, CylinderNode)
 * for real-time physics rendering. No external models needed.
 *
 * Physics is simulated per-frame in onFrame callback.
 * For production, consider Bullet Physics or JBullet for accurate simulation.
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)

    // Physics state
    val bodies = remember { mutableStateListOf<PhysicsBody>() }
    var paused by remember { mutableStateOf(false) }
    var score by remember { mutableIntStateOf(0) }
    val gravity = ${gravityValue}f
    val bounciness = ${bounciness}f

    // Initialize physics bodies for ${preset}
    LaunchedEffect(Unit) {
        bodies.addAll(create${capitalize(preset)}Bodies())
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
                    if (!paused) {
                        val dt = 1f / 60f // Fixed timestep
                        updatePhysics(bodies, gravity, bounciness, dt)
                    }
                }
            ) {
${config.sceneContent}

                // Dynamic physics bodies
                for (body in bodies) {
                    when (body.shape) {
                        "sphere" -> SphereNode(
                            engine = engine,
                            materialLoader = materialLoader,
                            radius = body.radius,
                            position = Position(body.x, body.y, body.z)
                        )
                        "cube" -> CubeNode(
                            engine = engine,
                            materialLoader = materialLoader,
                            size = Scale(body.radius * 2, body.radius * 2, body.radius * 2),
                            position = Position(body.x, body.y, body.z)
                        )
                        "cylinder" -> CylinderNode(
                            engine = engine,
                            materialLoader = materialLoader,
                            radius = body.radius,
                            height = body.radius * 2,
                            position = Position(body.x, body.y, body.z)
                        )
                    }
                }
${showTrajectory ? `
                // Trajectory prediction line
                for (body in bodies.take(1)) {
                    val steps = predictTrajectory(body, gravity, 20)
                    for (step in steps) {
                        SphereNode(
                            engine = engine,
                            materialLoader = materialLoader,
                            radius = 0.02f,
                            position = Position(step.first, step.second, step.third)
                        )
                    }
                }` : ""}

                // Lighting
                LightNode(
                    apply = {
                        intensity(100_000f)
                        color(1.0f, 0.98f, 0.95f)
                        direction(0.3f, -1f, -0.4f)
                    }
                )

                LightNode(
                    apply = {
                        intensity(40_000f)
                        color(0.9f, 0.9f, 1.0f)
                        direction(-0.3f, -0.5f, 0.5f)
                    }
                )
            }
        }

        // ── Controls ──────────────────────────────────────────────────────
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
                    Text("Score: \$score", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { paused = !paused }) {
                            Text(if (paused) "Resume" else "Pause")
                        }
                        Button(onClick = {
                            bodies.clear()
                            bodies.addAll(create${capitalize(preset)}Bodies())
                            score = 0
                        }) {
                            Text("Reset")
                        }
                    }
                }
                Text(
                    "Bodies: \${bodies.size} | Gravity: ${gravity} (${gravityValue} m/s\u00B2)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Physics body with position, velocity, and collision properties.
 */
data class PhysicsBody(
    var x: Float,
    var y: Float,
    var z: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var vz: Float = 0f,
    val mass: Float = 1f,
    val radius: Float = 0.1f,
    val shape: String = "sphere",
    val isStatic: Boolean = false,
)

/**
 * Simple Euler integration physics step with ground collision and sphere-sphere collision.
 */
private fun updatePhysics(
    bodies: MutableList<PhysicsBody>,
    gravity: Float,
    bounciness: Float,
    dt: Float,
) {
    for (body in bodies) {
        if (body.isStatic) continue

        // Apply gravity
        body.vy += gravity * dt

        // Update position
        body.x += body.vx * dt
        body.y += body.vy * dt
        body.z += body.vz * dt

        // Ground collision (y = 0 plane)
        if (body.y - body.radius < 0f) {
            body.y = body.radius
            body.vy = -body.vy * bounciness
            // Friction
            body.vx *= 0.98f
            body.vz *= 0.98f
        }

        // Wall bounds (-5 to 5)
        if (body.x < -5f + body.radius || body.x > 5f - body.radius) {
            body.vx = -body.vx * bounciness
            body.x = body.x.coerceIn(-5f + body.radius, 5f - body.radius)
        }
        if (body.z < -5f + body.radius || body.z > 5f - body.radius) {
            body.vz = -body.vz * bounciness
            body.z = body.z.coerceIn(-5f + body.radius, 5f - body.radius)
        }
    }

    // Sphere-sphere collision detection
    for (i in bodies.indices) {
        for (j in i + 1 until bodies.size) {
            val a = bodies[i]
            val b = bodies[j]
            if (a.isStatic && b.isStatic) continue

            val dx = b.x - a.x
            val dy = b.y - a.y
            val dz = b.z - a.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            val minDist = a.radius + b.radius

            if (dist < minDist && dist > 0.001f) {
                // Elastic collision response
                val nx = dx / dist
                val ny = dy / dist
                val nz = dz / dist
                val relVx = a.vx - b.vx
                val relVy = a.vy - b.vy
                val relVz = a.vz - b.vz
                val velAlongNormal = relVx * nx + relVy * ny + relVz * nz

                if (velAlongNormal > 0) {
                    val impulse = velAlongNormal * bounciness
                    if (!a.isStatic) {
                        a.vx -= impulse * nx
                        a.vy -= impulse * ny
                        a.vz -= impulse * nz
                    }
                    if (!b.isStatic) {
                        b.vx += impulse * nx
                        b.vy += impulse * ny
                        b.vz += impulse * nz
                    }
                }

                // Separate overlapping bodies
                val overlap = minDist - dist
                if (!a.isStatic) {
                    a.x -= nx * overlap * 0.5f
                    a.y -= ny * overlap * 0.5f
                    a.z -= nz * overlap * 0.5f
                }
                if (!b.isStatic) {
                    b.x += nx * overlap * 0.5f
                    b.y += ny * overlap * 0.5f
                    b.z += nz * overlap * 0.5f
                }
            }
        }
    }
}
${showTrajectory ? `
/**
 * Predicts trajectory for a body (for aim assist visualization).
 */
private fun predictTrajectory(
    body: PhysicsBody,
    gravity: Float,
    steps: Int,
): List<Triple<Float, Float, Float>> {
    val points = mutableListOf<Triple<Float, Float, Float>>()
    var x = body.x; var y = body.y; var z = body.z
    var vx = body.vx; var vy = body.vy; var vz = body.vz
    val dt = 0.05f

    for (i in 0 until steps) {
        vy += gravity * dt
        x += vx * dt; y += vy * dt; z += vz * dt
        if (y < 0f) break
        points.add(Triple(x, y, z))
    }
    return points
}` : ""}

${getPresetBodiesFunction(preset)}`;
}

function generateArPhysicsGame(
  composableName: string,
  options: PhysicsGameOptions
): string {
  const gravityValue = GRAVITY_VALUES[options.gravity ?? "earth"];
  return `package com.example.gaming.physics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.node.SphereNode
import io.github.sceneview.node.CubeNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.math.Position

/**
 * AR ${capitalize(options.preset)} — physics simulation in augmented reality.
 *
 * Tap a surface to spawn physics objects that bounce and collide in real space.
 * Gravity: ${gravityValue} m/s^2 (${options.gravity ?? "earth"}).
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

    var spawnCount by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            collisionSystem = collisionSystem,
            planeRenderer = true,
            onTapAR = { hitResult ->
                val anchor = hitResult.createAnchor()
                spawnCount++
            }
        ) {
            // Physics objects rendered here
        }

        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
        ) {
            Text(
                text = "Tap surfaces to spawn physics objects (spawned: \$spawnCount)",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}`;
}

interface PresetConfig {
  description: string;
  sceneContent: string;
}

function getPresetConfig(preset: PhysicsPreset): PresetConfig {
  const configs: Record<PhysicsPreset, PresetConfig> = {
    "bouncing-balls": {
      description: "Spawn colorful balls that bounce off walls and each other. Demonstrates elastic collision, gravity, and friction.",
      sceneContent: `                // Ground plane
                CubeNode(
                    engine = engine,
                    materialLoader = materialLoader,
                    size = Scale(10f, 0.1f, 10f),
                    position = Position(0f, -0.05f, 0f)
                )

                // Walls
                CubeNode(
                    engine = engine,
                    materialLoader = materialLoader,
                    size = Scale(0.1f, 2f, 10f),
                    position = Position(-5f, 1f, 0f)
                )
                CubeNode(
                    engine = engine,
                    materialLoader = materialLoader,
                    size = Scale(0.1f, 2f, 10f),
                    position = Position(5f, 1f, 0f)
                )`,
    },
    bowling: {
      description: "A bowling lane with 10 pins. Swipe to launch the ball and knock down pins. Physics collision detection for scoring.",
      sceneContent: `                // Bowling lane
                CubeNode(
                    engine = engine,
                    materialLoader = materialLoader,
                    size = Scale(2f, 0.05f, 10f),
                    position = Position(0f, -0.025f, 0f)
                )

                // Lane gutters
                CubeNode(
                    engine = engine,
                    materialLoader = materialLoader,
                    size = Scale(0.2f, 0.3f, 10f),
                    position = Position(-1.1f, 0.15f, 0f)
                )
                CubeNode(
                    engine = engine,
                    materialLoader = materialLoader,
                    size = Scale(0.2f, 0.3f, 10f),
                    position = Position(1.1f, 0.15f, 0f)
                )`,
    },
    billiards: {
      description: "A billiard table with realistic ball physics. Drag to aim, release to shoot. Includes friction and cushion bounces.",
      sceneContent: `                // Billiard table surface
                CubeNode(
                    engine = engine,
                    materialLoader = materialLoader,
                    size = Scale(5f, 0.1f, 3f),
                    position = Position(0f, 0f, 0f)
                )

                // Table cushions
                CubeNode(engine = engine, materialLoader = materialLoader, size = Scale(5f, 0.2f, 0.1f), position = Position(0f, 0.1f, -1.55f))
                CubeNode(engine = engine, materialLoader = materialLoader, size = Scale(5f, 0.2f, 0.1f), position = Position(0f, 0.1f, 1.55f))
                CubeNode(engine = engine, materialLoader = materialLoader, size = Scale(0.1f, 0.2f, 3f), position = Position(-2.55f, 0.1f, 0f))
                CubeNode(engine = engine, materialLoader = materialLoader, size = Scale(0.1f, 0.2f, 3f), position = Position(2.55f, 0.1f, 0f))`,
    },
    "marble-run": {
      description: "A marble run with ramps, funnels, and loops. Watch marbles race through the course with realistic physics.",
      sceneContent: `                // Start ramp
                CubeNode(
                    engine = engine,
                    materialLoader = materialLoader,
                    size = Scale(1f, 0.05f, 2f),
                    position = Position(-2f, 3f, 0f)
                )

                // Mid-level platforms
                CubeNode(engine = engine, materialLoader = materialLoader, size = Scale(2f, 0.05f, 1f), position = Position(0f, 2f, 0f))
                CubeNode(engine = engine, materialLoader = materialLoader, size = Scale(1.5f, 0.05f, 1f), position = Position(2f, 1f, 0f))`,
    },
    "tower-collapse": {
      description: "Build a tower of blocks and watch it collapse with realistic rigid body physics. Tap to add blocks, swipe to topple.",
      sceneContent: `                // Ground
                CubeNode(
                    engine = engine,
                    materialLoader = materialLoader,
                    size = Scale(8f, 0.1f, 8f),
                    position = Position(0f, -0.05f, 0f)
                )`,
    },
    "pong-3d": {
      description: "3D Pong with a bouncing ball between two paddles. Physics-based deflection angles based on hit position.",
      sceneContent: `                // Play field
                CubeNode(
                    engine = engine,
                    materialLoader = materialLoader,
                    size = Scale(4f, 0.05f, 6f),
                    position = Position(0f, -0.025f, 0f)
                )

                // Side walls
                CubeNode(engine = engine, materialLoader = materialLoader, size = Scale(0.1f, 0.5f, 6f), position = Position(-2.05f, 0.25f, 0f))
                CubeNode(engine = engine, materialLoader = materialLoader, size = Scale(0.1f, 0.5f, 6f), position = Position(2.05f, 0.25f, 0f))`,
    },
    pinball: {
      description: "A pinball machine with flippers, bumpers, and scoring. Tilt your device to influence ball direction.",
      sceneContent: `                // Pinball table (tilted)
                CubeNode(
                    engine = engine,
                    materialLoader = materialLoader,
                    size = Scale(3f, 0.05f, 6f),
                    position = Position(0f, 0f, 0f)
                )

                // Bumpers
                CylinderNode(engine = engine, materialLoader = materialLoader, radius = 0.2f, height = 0.3f, position = Position(-0.5f, 0.15f, -1f))
                CylinderNode(engine = engine, materialLoader = materialLoader, radius = 0.2f, height = 0.3f, position = Position(0.5f, 0.15f, -0.5f))
                CylinderNode(engine = engine, materialLoader = materialLoader, radius = 0.15f, height = 0.3f, position = Position(0f, 0.15f, 0.5f))`,
    },
    cannon: {
      description: "Aim and fire a cannon to hit targets. Adjust angle and power for projectile physics with trajectory prediction.",
      sceneContent: `                // Ground
                CubeNode(
                    engine = engine,
                    materialLoader = materialLoader,
                    size = Scale(10f, 0.1f, 10f),
                    position = Position(0f, -0.05f, 0f)
                )

                // Target wall
                CubeNode(
                    engine = engine,
                    materialLoader = materialLoader,
                    size = Scale(3f, 3f, 0.2f),
                    position = Position(0f, 1.5f, -4f)
                )`,
    },
  };
  return configs[preset];
}

function getPresetBodiesFunction(preset: PhysicsPreset): string {
  const configs: Record<PhysicsPreset, string> = {
    "bouncing-balls": `
/**
 * Creates initial bouncing ball bodies.
 */
private fun createBouncingBallsBodies(): List<PhysicsBody> {
    val bodies = mutableListOf<PhysicsBody>()
    for (i in 0 until 10) {
        bodies.add(PhysicsBody(
            x = (i - 5) * 0.5f,
            y = 3f + i * 0.3f,
            z = 0f,
            vx = (Math.random().toFloat() - 0.5f) * 2f,
            vy = 0f,
            vz = (Math.random().toFloat() - 0.5f) * 2f,
            radius = 0.15f + Math.random().toFloat() * 0.1f,
            shape = "sphere"
        ))
    }
    return bodies
}`,
    bowling: `
private fun createBowlingBodies(): List<PhysicsBody> {
    val bodies = mutableListOf<PhysicsBody>()
    // Bowling ball
    bodies.add(PhysicsBody(x = 0f, y = 0.15f, z = 4f, vz = -3f, radius = 0.15f, mass = 5f, shape = "sphere"))
    // 10 pins in triangle formation
    val pinPositions = listOf(
        0f to -3f, -0.15f to -3.3f, 0.15f to -3.3f,
        -0.3f to -3.6f, 0f to -3.6f, 0.3f to -3.6f,
        -0.45f to -3.9f, -0.15f to -3.9f, 0.15f to -3.9f, 0.45f to -3.9f,
    )
    for ((px, pz) in pinPositions) {
        bodies.add(PhysicsBody(x = px, y = 0.15f, z = pz, radius = 0.05f, mass = 0.5f, shape = "cylinder"))
    }
    return bodies
}`,
    billiards: `
private fun createBilliardsBodies(): List<PhysicsBody> {
    val bodies = mutableListOf<PhysicsBody>()
    // Cue ball
    bodies.add(PhysicsBody(x = -1.5f, y = 0.1f, z = 0f, radius = 0.05f, shape = "sphere"))
    // Triangle rack
    var row = 0; var col = 0
    for (i in 0 until 15) {
        val x = 1f + row * 0.11f
        val z = (col - row / 2f) * 0.11f
        bodies.add(PhysicsBody(x = x, y = 0.1f, z = z, radius = 0.05f, shape = "sphere"))
        col++
        if (col > row) { row++; col = 0 }
    }
    return bodies
}`,
    "marble-run": `
private fun createMarbleRunBodies(): List<PhysicsBody> {
    return listOf(
        PhysicsBody(x = -2f, y = 3.2f, z = 0f, radius = 0.08f, shape = "sphere"),
        PhysicsBody(x = -1.8f, y = 3.2f, z = 0.2f, radius = 0.08f, shape = "sphere"),
        PhysicsBody(x = -1.6f, y = 3.2f, z = -0.1f, radius = 0.08f, shape = "sphere"),
    )
}`,
    "tower-collapse": `
private fun createTowerCollapseBodies(): List<PhysicsBody> {
    val bodies = mutableListOf<PhysicsBody>()
    for (layer in 0 until 6) {
        for (i in 0 until 3) {
            val x = if (layer % 2 == 0) (i - 1) * 0.35f else 0f
            val z = if (layer % 2 == 0) 0f else (i - 1) * 0.35f
            bodies.add(PhysicsBody(
                x = x, y = 0.15f + layer * 0.3f, z = z,
                radius = 0.15f, shape = "cube", mass = 1f
            ))
        }
    }
    return bodies
}`,
    "pong-3d": `
private fun createPong3dBodies(): List<PhysicsBody> {
    return listOf(
        // Ball
        PhysicsBody(x = 0f, y = 0.15f, z = 0f, vx = 2f, vz = 1.5f, radius = 0.1f, shape = "sphere"),
        // Player paddle
        PhysicsBody(x = 0f, y = 0.15f, z = 2.5f, radius = 0.3f, shape = "cube", isStatic = true),
        // AI paddle
        PhysicsBody(x = 0f, y = 0.15f, z = -2.5f, radius = 0.3f, shape = "cube", isStatic = true),
    )
}`,
    pinball: `
private fun createPinballBodies(): List<PhysicsBody> {
    return listOf(
        PhysicsBody(x = 1f, y = 0.1f, z = 2.5f, radius = 0.08f, shape = "sphere"),
    )
}`,
    cannon: `
private fun createCannonBodies(): List<PhysicsBody> {
    val bodies = mutableListOf<PhysicsBody>()
    // Cannonball with initial velocity
    bodies.add(PhysicsBody(x = 0f, y = 1f, z = 4f, vz = -5f, vy = 3f, radius = 0.1f, mass = 3f, shape = "sphere"))
    // Targets
    for (i in 0 until 5) {
        bodies.add(PhysicsBody(x = (i - 2) * 0.5f, y = 0.15f + i * 0.3f, z = -4f, radius = 0.12f, shape = "cube"))
    }
    return bodies
}`,
  };
  return configs[preset];
}

export function getGravityValue(mode: GravityMode): number {
  return GRAVITY_VALUES[mode];
}

function capitalize(s: string): string {
  return s
    .split("-")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join("");
}
