package io.github.sceneview.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlin.math.cos
import kotlin.math.sin

/**
 * Desktop Demo — Compose Desktop wireframe renderer (NOT SceneView).
 *
 * IMPORTANT: This app does NOT use SceneView or Filament.
 * It is a standalone Compose Canvas wireframe renderer that serves as a
 * UI placeholder for a future Filament JNI desktop integration.
 *
 * What this IS:  Compose Canvas 2D drawing with manual perspective projection.
 * What this is NOT: GPU-accelerated 3D rendering, glTF model loading, PBR materials.
 *
 * To run: ./gradlew :samples:desktop-demo:run
 */

// SceneView brand colors (M3 design system — see DESIGN.md)
private val SceneViewBlue = Color(0xFFA4C1FF)       // dark mode primary
private val SceneViewDarkBlue = Color(0xFF005BC1)    // brand blue
private val SceneViewSurface = Color(0xFF0D1117)
private val SceneViewSurfaceVariant = Color(0xFF161B22)

private val SceneViewColorScheme = darkColorScheme(
    primary = Color(0xFFA4C1FF),
    primaryContainer = Color(0xFF00458F),
    surface = SceneViewSurface,
    surfaceVariant = SceneViewSurfaceVariant,
    background = SceneViewSurface,
    onPrimary = Color(0xFF002F65),
    onSurface = Color.White,
    onBackground = Color.White,
)

fun main() = application {
    val windowState = rememberWindowState(width = 1280.dp, height = 800.dp)

    Window(
        onCloseRequest = ::exitApplication,
        title = "SceneView Desktop Demo — Software Renderer (Preview)",
        state = windowState,
    ) {
        MaterialTheme(colorScheme = SceneViewColorScheme) {
            DesktopApp()
        }
    }
}

@Composable
fun DesktopApp() {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("3D Viewer", "Wireframe", "About")

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SceneViewSurfaceVariant,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "SceneView",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = SceneViewBlue
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tabs.forEachIndexed { index, title ->
                            FilterChip(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                label = { Text(title, fontSize = 13.sp) }
                            )
                        }
                    }
                    Text(
                        "Software Renderer (Preview) • Not SceneView",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            // Content
            when (selectedTab) {
                0 -> SoftwareRenderer3D()
                1 -> WireframeCubeViewer()
                2 -> AboutScreen()
            }
        }
    }
}

/**
 * Software 3D renderer — renders a rotating cube using Compose Canvas.
 * Uses perspective projection and basic wireframe rendering.
 * This demonstrates the rendering pipeline; Filament JNI will replace this.
 */
@Composable
fun SoftwareRenderer3D() {
    val transition = rememberInfiniteTransition()
    val angleY by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing))
    )
    val angleX by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing))
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawSoftware3DScene(angleX, angleY)
        }

        // Overlay info
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
        ) {
            Text(
                "Software Renderer (Not SceneView)",
                style = MaterialTheme.typography.labelMedium,
                color = SceneViewBlue
            )
            Text(
                "Compose Canvas 2D • No Filament • No GPU acceleration",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f)
            )
        }

        // Platform badge
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            color = SceneViewBlue.copy(alpha = 0.15f)
        ) {
            Text(
                "Windows • macOS • Linux",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = SceneViewBlue
            )
        }
    }
}

/**
 * Wireframe cube viewer with colored faces.
 */
@Composable
fun WireframeCubeViewer() {
    val transition = rememberInfiniteTransition()
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing))
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawWireframeScene(angle)
        }
    }
}

@Composable
fun AboutScreen() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Desktop Demo",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = SceneViewBlue
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF332200)
            ) {
                Text(
                    "Software Renderer Preview — This app does NOT use SceneView or Filament",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFB74D)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Compose Canvas wireframe renderer with manual perspective projection.\n" +
                    "A UI placeholder for future Filament JNI desktop integration.",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(16.dp))

            val platforms = listOf(
                "Android" to "Filament • Jetpack Compose (production)",
                "iOS / macOS / visionOS" to "RealityKit • SwiftUI (alpha)",
                "Web" to "Filament.js • Kotlin/JS (alpha)",
                "Desktop" to "Software wireframe only (Filament JNI not yet available)",
                "Android TV" to "Filament • Compose TV (alpha)",
                "Flutter" to "PlatformView bridge (alpha)",
                "React Native" to "Fabric bridge (alpha)"
            )

            platforms.forEach { (platform, tech) ->
                Surface(
                    modifier = Modifier.fillMaxWidth(0.6f),
                    shape = RoundedCornerShape(12.dp),
                    color = SceneViewSurfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(platform, fontWeight = FontWeight.Medium, color = Color.White)
                        Text(tech, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "github.com/sceneview/sceneview",
                style = MaterialTheme.typography.bodySmall,
                color = SceneViewBlue.copy(alpha = 0.7f)
            )
        }
    }
}

// ─── Software 3D rendering functions ───

private data class Vec3(val x: Float, val y: Float, val z: Float)

private fun rotateY(v: Vec3, angle: Float): Vec3 {
    val rad = Math.toRadians(angle.toDouble())
    val cos = cos(rad).toFloat()
    val sin = sin(rad).toFloat()
    return Vec3(v.x * cos + v.z * sin, v.y, -v.x * sin + v.z * cos)
}

private fun rotateX(v: Vec3, angle: Float): Vec3 {
    val rad = Math.toRadians(angle.toDouble())
    val cos = cos(rad).toFloat()
    val sin = sin(rad).toFloat()
    return Vec3(v.x, v.y * cos - v.z * sin, v.y * sin + v.z * cos)
}

private fun project(v: Vec3, width: Float, height: Float, fov: Float = 4f): Offset {
    val z = v.z + fov
    val scale = fov / z.coerceAtLeast(0.1f)
    return Offset(
        width / 2 + v.x * scale * width / 4,
        height / 2 - v.y * scale * height / 4
    )
}

@Suppress("LongMethod") // software wireframe renderer — many drawLine/drawCircle calls are inherently verbose
private fun DrawScope.drawSoftware3DScene(angleX: Float, angleY: Float) {
    // Background gradient
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF0D1117), Color(0xFF161B22), Color(0xFF0D1117))
        )
    )

    // Grid floor
    val gridColor = Color(0xFF1A2332)
    for (i in -5..5) {
        val left = project(rotateX(rotateY(Vec3(i.toFloat(), -1f, -5f), 0f), 20f), size.width, size.height)
        val right = project(rotateX(rotateY(Vec3(i.toFloat(), -1f, 5f), 0f), 20f), size.width, size.height)
        drawLine(gridColor, left, right, 1f)
        val near = project(rotateX(rotateY(Vec3(-5f, -1f, i.toFloat()), 0f), 20f), size.width, size.height)
        val far = project(rotateX(rotateY(Vec3(5f, -1f, i.toFloat()), 0f), 20f), size.width, size.height)
        drawLine(gridColor, near, far, 1f)
    }

    // Cube vertices
    val s = 0.8f
    val vertices = listOf(
        Vec3(-s, -s, -s), Vec3(s, -s, -s), Vec3(s, s, -s), Vec3(-s, s, -s),
        Vec3(-s, -s, s), Vec3(s, -s, s), Vec3(s, s, s), Vec3(-s, s, s)
    )

    // Transform
    val transformed = vertices.map { v ->
        rotateX(rotateY(v, angleY), angleX * 0.5f + 15f)
    }
    val projected = transformed.map { project(it, size.width, size.height) }

    // Edges
    val edges = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 0,  // front
        4 to 5, 5 to 6, 6 to 7, 7 to 4,  // back
        0 to 4, 1 to 5, 2 to 6, 3 to 7   // sides
    )

    // Draw filled faces with transparency
    val faces = listOf(
        listOf(0, 1, 2, 3) to Color(0x30A4C1FF), // front - blue
        listOf(4, 5, 6, 7) to Color(0x30005BC1), // back - dark blue
        listOf(0, 1, 5, 4) to Color(0x3000BCD4), // bottom - cyan
        listOf(2, 3, 7, 6) to Color(0x3000BCD4), // top - cyan
        listOf(0, 3, 7, 4) to Color(0x304CAF50), // left - green
        listOf(1, 2, 6, 5) to Color(0x304CAF50), // right - green
    )

    faces.forEach { (indices, color) ->
        val path = Path().apply {
            moveTo(projected[indices[0]].x, projected[indices[0]].y)
            indices.drop(1).forEach { lineTo(projected[it].x, projected[it].y) }
            close()
        }
        drawPath(path, color)
    }

    // Draw edges
    edges.forEach { (a, b) ->
        drawLine(
            color = Color(0xFFA4C1FF),
            start = projected[a],
            end = projected[b],
            strokeWidth = 2f
        )
    }

    // Draw vertices
    projected.forEach { p ->
        drawCircle(Color(0xFFD4E3FF), 4f, p)
    }

    // Axes
    val origin = project(Vec3(0f, 0f, 0f), size.width, size.height)
    val xAxis = project(rotateX(rotateY(Vec3(1.5f, 0f, 0f), angleY), angleX * 0.5f + 15f), size.width, size.height)
    val yAxis = project(rotateX(rotateY(Vec3(0f, 1.5f, 0f), angleY), angleX * 0.5f + 15f), size.width, size.height)
    val zAxis = project(rotateX(rotateY(Vec3(0f, 0f, 1.5f), angleY), angleX * 0.5f + 15f), size.width, size.height)
    drawLine(Color.Red, origin, xAxis, 1.5f)
    drawLine(Color.Green, origin, yAxis, 1.5f)
    drawLine(Color(0xFF4444FF), origin, zAxis, 1.5f)
}

private fun DrawScope.drawWireframeScene(angle: Float) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF0D1117), Color(0xFF1A1A2E))
        )
    )

    // Draw multiple wireframe shapes
    val shapes = listOf(
        Triple(-2f, 0f, "Cube"),
        Triple(0f, 0f, "Octahedron"),
        Triple(2f, 0f, "Diamond"),
    )

    shapes.forEachIndexed { index, (offsetX, offsetY, _) ->
        val color = when (index) {
            0 -> Color(0xFFA4C1FF)
            1 -> Color(0xFF4CAF50)
            else -> Color(0xFFFF9800)
        }
        val phaseAngle = angle + index * 120f

        when (index) {
            0 -> drawWireframeCube(offsetX, offsetY, phaseAngle, color)
            1 -> drawWireframeOctahedron(offsetX, offsetY, phaseAngle, color)
            2 -> drawWireframeDiamond(offsetX, offsetY, phaseAngle, color)
        }
    }
}

private fun DrawScope.drawWireframeCube(ox: Float, oy: Float, angle: Float, color: Color) {
    val s = 0.5f
    val verts = listOf(
        Vec3(-s + ox, -s + oy, -s), Vec3(s + ox, -s + oy, -s),
        Vec3(s + ox, s + oy, -s), Vec3(-s + ox, s + oy, -s),
        Vec3(-s + ox, -s + oy, s), Vec3(s + ox, -s + oy, s),
        Vec3(s + ox, s + oy, s), Vec3(-s + ox, s + oy, s)
    )
    val proj = verts.map { project(rotateX(rotateY(it, angle), 25f), size.width, size.height) }
    val edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
    edges.forEach { (a, b) -> drawLine(color, proj[a], proj[b], 1.5f) }
}

private fun DrawScope.drawWireframeOctahedron(ox: Float, oy: Float, angle: Float, color: Color) {
    val s = 0.6f
    val verts = listOf(
        Vec3(ox, s + oy, 0f), Vec3(ox, -s + oy, 0f),
        Vec3(s + ox, oy, 0f), Vec3(-s + ox, oy, 0f),
        Vec3(ox, oy, s), Vec3(ox, oy, -s)
    )
    val proj = verts.map { project(rotateX(rotateY(it, angle), 25f), size.width, size.height) }
    val edges = listOf(0 to 2, 0 to 3, 0 to 4, 0 to 5, 1 to 2, 1 to 3, 1 to 4, 1 to 5, 2 to 4, 4 to 3, 3 to 5, 5 to 2)
    edges.forEach { (a, b) -> drawLine(color, proj[a], proj[b], 1.5f) }
}

private fun DrawScope.drawWireframeDiamond(ox: Float, oy: Float, angle: Float, color: Color) {
    val s = 0.4f
    val h = 0.8f
    val verts = listOf(
        Vec3(ox, h + oy, 0f),
        Vec3(s + ox, oy, s), Vec3(s + ox, oy, -s),
        Vec3(-s + ox, oy, s), Vec3(-s + ox, oy, -s),
        Vec3(ox, -h * 0.5f + oy, 0f)
    )
    val proj = verts.map { project(rotateX(rotateY(it, angle), 25f), size.width, size.height) }
    val edges = listOf(0 to 1, 0 to 2, 0 to 3, 0 to 4, 1 to 2, 2 to 4, 4 to 3, 3 to 1, 5 to 1, 5 to 2, 5 to 3, 5 to 4)
    edges.forEach { (a, b) -> drawLine(color, proj[a], proj[b], 1.5f) }
}
