package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.CustomGeometryDemo

/**
 * "Custom Geometry" — a torus knot whose vertices, normals and UVs are generated in Kotlin
 * at runtime and uploaded straight into a Filament vertex buffer (#3423).
 *
 * The catalog's other geometry entry, `geometry`, covers the built-in primitives. This one
 * is the entry point for "the shape I need is not a primitive". The retired `custom-mesh`
 * and `shape` deep-link ids stay routable through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
object CustomGeometryFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "custom-geometry",
        titleRes = R.string.demo_custom_geometry_title,
        subtitleRes = R.string.demo_custom_geometry_subtitle,
        category = DemoCategory.ADVANCED,
        icon = Icons.Filled.Hexagon,
        order = 10,
        tags = setOf("geometry", "mesh", "procedural", "vertices", "wireframe", "knot"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        CustomGeometryDemo(onBack)
    }
}
