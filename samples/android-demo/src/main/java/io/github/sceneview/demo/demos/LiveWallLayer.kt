package io.github.sceneview.demo.demos

import androidx.compose.ui.graphics.Color
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.LiveWallMotion
import io.github.sceneview.demo.demos.internal.LiveWallSnapshot
import io.github.sceneview.demo.demos.internal.WallMesh
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.loaders.MaterialLoader
import kotlin.math.sqrt

/**
 * The walls recognised live (#4540, #4732), drawn the one way every AR screen draws them: each wall
 * a translucent quad in Measure's wall colour ([WallMesh.FILL_ALPHA] of `accentProgress`) rising
 * from its floor line as [LiveWallMotion] eases it, outlined in the same colour. 3D Scan's live
 * view and Measure both hang [nodes] in their scene and call [sync] once a frame; nothing here
 * knows which screen it is on.
 */
internal class LiveWallLayer(engine: Engine, private val materialLoader: MaterialLoader) {
    private val fillMaterial = layerMaterial(ArOverlay.accentProgress.copy(alpha = WallMesh.FILL_ALPHA))
    private val lineMaterial = layerMaterial(ArOverlay.accentProgress)

    private val fill = DebugLayerNode(engine, fillMaterial, priority = FILL_PRIORITY)
    private val lines = DebugLayerNode(engine, lineMaterial, priority = LINE_PRIORITY)

    val nodes = listOf(fill, lines)

    private val fillMesh = DebugMesh()
    private val lineMesh = DebugMesh()

    /**
     * Eases [motion] [dt] seconds towards [snapshot] and re-uploads the walls if they moved, or
     * hides them while not [shown]. On the main thread.
     */
    fun sync(motion: LiveWallMotion, snapshot: LiveWallSnapshot, dt: Float, shown: Boolean = true) {
        nodes.forEach { if (it.isVisible != shown) it.isVisible = shown }
        if (!shown) return
        if (!motion.step(snapshot, dt)) return
        fillMesh.clear()
        lineMesh.clear()
        for (wall in motion.shown) {
            val height = motion.height * wall.rise
            if (height <= 0f) continue
            val y = motion.floorY
            val base = fillMesh.vertexCount
            WallMesh.quad(wall.ax, y, wall.az, wall.bx, y, wall.bz, height) { x, vy, z -> fillMesh.vertex(x, vy, z) }
            fillMesh.quad(base, base + 1, base + 2, base + 3)
            outline(lineMesh, wall.ax, wall.az, wall.bx, wall.bz, y, height)
        }
        fill.upload(fillMesh)
        lines.upload(lineMesh)
    }

    /** On the main thread, after [nodes] left the scene. */
    fun destroy() {
        nodes.forEach { it.destroy() }
        listOf(fillMaterial, lineMaterial).forEach { materialLoader.destroyMaterialInstance(it) }
    }

    private fun layerMaterial(color: Color): MaterialInstance =
        materialLoader.createUnlitColorInstance(color).apply { setCullingMode(Material.CullingMode.NONE) }

    companion object {
        /** Under the points (the debug layers' default 4): the cloud reads through the walls. */
        const val FILL_PRIORITY = 2
        const val LINE_PRIORITY = 3

        /** A wall's outline, in metres: a hairline from across the room, not a bar up close. */
        const val LINE_M = 0.012f

        /** The wall's base, top and two sides as thin ribbons in its own plane. */
        fun outline(mesh: DebugMesh, ax: Float, az: Float, bx: Float, bz: Float, y: Float, height: Float) {
            val length = sqrt((bx - ax) * (bx - ax) + (bz - az) * (bz - az))
            if (length < 1e-4f) return
            val ux = (bx - ax) / length * LINE_M
            val uz = (bz - az) / length * LINE_M
            val half = LINE_M / 2f
            // Base and top: ribbons along the wall, LINE_M tall.
            ribbon(mesh, ax, y, az, bx, y, bz, 0f, LINE_M, 0f)
            ribbon(mesh, ax, y + height - half, az, bx, y + height - half, bz, 0f, LINE_M, 0f)
            // Sides: ribbons up the wall, LINE_M wide, inside its ends.
            ribbon(mesh, ax + ux / 2f, y, az + uz / 2f, ax + ux / 2f, y + height, az + uz / 2f, ux, 0f, uz)
            ribbon(mesh, bx - ux / 2f, y, bz - uz / 2f, bx - ux / 2f, y + height, bz - uz / 2f, ux, 0f, uz)
        }

        /** A ribbon from a to b, [wx]/[wy]/[wz] wide across it, centred on the line. */
        @Suppress("LongParameterList") // two points and a width vector, flat for a render loop
        fun ribbon(
            mesh: DebugMesh,
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            wx: Float, wy: Float, wz: Float,
        ) {
            val hx = wx / 2f
            val hy = wy / 2f
            val hz = wz / 2f
            val a = mesh.vertex(ax - hx, ay - hy, az - hz)
            val b = mesh.vertex(bx - hx, by - hy, bz - hz)
            val c = mesh.vertex(bx + hx, by + hy, bz + hz)
            val d = mesh.vertex(ax + hx, ay + hy, az + hz)
            mesh.quad(a, b, c, d)
        }
    }
}
