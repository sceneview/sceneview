package io.github.sceneview.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import io.github.sceneview.compose.rememberCameraState
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.compose.ModelSource
import io.github.sceneview.compose.SceneViewer
import io.github.sceneview.desktop.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

// DESIGN.md dark tokens — do not invent colors.
private val Primary = Color(0xFFA4C1FF)
private val SurfaceBg = Color(0xFF0D1117)
private val SurfaceDim = Color(0xFF161B22)
private val OnSurface = Color(0xFFF3F4F6)
private val OnSurfaceDim = Color(0xFF9CA3AF)

private val DesktopScheme = darkColorScheme(
    primary = Primary,
    surface = SurfaceBg,
    background = SurfaceBg,
    onPrimary = Color(0xFF002F65),
    onSurface = OnSurface,
    onBackground = OnSurface,
)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SceneView Desktop",
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
    ) {
        MaterialTheme(colorScheme = DesktopScheme) {
            DesktopApp()
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
fun DesktopApp() {
    val bytes by produceState<ByteArray?>(null) {
        value = Res.readBytes("files/models/Duck.glb")
    }
    val model = bytes?.let { ModelSource.Bytes(it) }
    // Duck.glb is authored in centimetres then scaled 0.01 at the root. Origin is
    // near the feet; the mesh centroid is ~ (0.13, 0.87, -0.04). Look there so
    // the bird sits in the middle of the frame instead of floating in the top half.
    val camera = rememberCameraState(
        target = Float3(0.13f, 0.87f, -0.04f),
        distance = 3.2f,
        elevation = 8f,
    )
    LaunchedEffect(camera) {
        while (isActive) {
            withFrameNanos {
                camera.azimuth += AUTO_ORBIT_DEGREES_PER_FRAME
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = SurfaceBg) {
        Box(Modifier.fillMaxSize()) {
            if (model != null) {
                SceneViewer(
                    model = model,
                    modifier = Modifier.fillMaxSize(),
                    camera = camera,
                )
            }
            Column(
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "SceneView",
                    color = Primary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "SceneViewer  ·  desktop",
                    color = OnSurfaceDim,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .background(SurfaceDim.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    "io.github.sceneview.compose.SceneViewer",
                    color = Primary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    "auto-orbit  ·  drag to steer  ·  scroll to zoom",
                    color = OnSurfaceDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private const val AUTO_ORBIT_DEGREES_PER_FRAME = 0.35f
