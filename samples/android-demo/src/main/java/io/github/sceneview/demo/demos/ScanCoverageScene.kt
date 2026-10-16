package io.github.sceneview.demo.demos

import androidx.annotation.MainThread
import androidx.compose.ui.graphics.Color
import com.google.android.filament.Engine
import com.google.android.filament.Material
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.ScanCoverage
import io.github.sceneview.demo.demos.internal.ScanCoverageGeometry
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setColor

/** Four persistent coverage tiers and three short-lived batches of newly seen cells. */
@MainThread
internal class ScanCoverageScene(engine: Engine, private val materialLoader: MaterialLoader) {
    private val tierAlphas = floatArrayOf(FIRST_ALPHA, SECOND_ALPHA, DENSE_ALPHA, FULL_ALPHA)
    private val materials = tierAlphas.map { material(ArOverlay.accentProgress.copy(alpha = it)) } +
        List(GLOW_BATCHES) { material(ArOverlay.onScrim.copy(alpha = GLOW_ALPHA)) }
    val nodes = materials.mapIndexed { index, material ->
        DebugLayerNode(engine, material, if (index < TIERS) PAINT_PRIORITY else GLOW_PRIORITY)
    }
    private val meshes = List(TIERS) { DebugMesh() }
    private val fresh = DebugMesh()
    private val bornAt = LongArray(GLOW_BATCHES) { NEVER }
    private var source: ScanCoverage? = null
    private var version = -1
    private var uploadedAt = NEVER
    private var lastSeq = -1
    private var nextGlow = 0

    private fun material(color: Color) = materialLoader.createUnlitColorInstance(color).apply {
        setCullingMode(Material.CullingMode.NONE)
        // Coplanar tiers and glow must blend without writing over each other's depth.
        setDepthWrite(false)
    }

    /** Called on the AR frame's main thread; only the glow's colour changes between uploads. */
    fun update(coverage: ScanCoverage?, nanos: Long) {
        if (coverage !== source) {
            source = coverage
            nodes.forEach { it.upload(null) }
            bornAt.fill(NEVER)
            version = -1
            uploadedAt = NEVER
            lastSeq = -1
            nextGlow = 0
        }
        fade(nanos)
        if (coverage == null || coverage.version == version) return
        if (uploadedAt != NEVER && nanos - uploadedAt < UPLOAD_INTERVAL_NS) return
        // Read before the locked pass: a concurrent observation will trigger another upload.
        version = coverage.version
        uploadedAt = nanos
        meshes.forEach { it.clear() }
        fresh.clear()
        var newest = lastSeq
        coverage.forEachCell { cell ->
            if (cell.tier > 0) {
                ScanCoverageGeometry.append(meshes[cell.tier - 1], cell, coverage.cellM)
                if (cell.seq > lastSeq) ScanCoverageGeometry.append(fresh, cell, coverage.cellM)
            }
            newest = maxOf(newest, cell.seq)
        }
        lastSeq = newest
        meshes.forEachIndexed { index, mesh -> nodes[index].upload(mesh) }
        if (!fresh.isEmpty) {
            val index = TIERS + nextGlow
            materials[index].setColor(ArOverlay.onScrim.copy(alpha = GLOW_ALPHA))
            nodes[index].upload(fresh)
            bornAt[nextGlow] = nanos
            nextGlow = (nextGlow + 1) % GLOW_BATCHES
        }
    }

    private fun fade(nanos: Long) {
        bornAt.forEachIndexed { batch, born ->
            if (born == NEVER) return@forEachIndexed
            val remaining = (1f - (nanos - born).toFloat() / GLOW_DURATION_NS).coerceIn(0f, 1f)
            materials[TIERS + batch].setColor(ArOverlay.onScrim.copy(alpha = GLOW_ALPHA * remaining))
            if (remaining == 0f) {
                nodes[TIERS + batch].upload(null)
                bornAt[batch] = NEVER
            }
        }
    }

    /** The scene detaches first; nodes release their buffers before their materials go away. */
    fun destroy() {
        nodes.forEach { it.destroy() }
        materials.forEach { materialLoader.destroyMaterialInstance(it) }
    }

    private companion object {
        const val TIERS = 4
        const val GLOW_BATCHES = 3
        const val FIRST_ALPHA = 0.45f
        const val SECOND_ALPHA = 0.55f
        const val DENSE_ALPHA = 0.65f
        const val FULL_ALPHA = 0.35f
        const val GLOW_ALPHA = 0.9f
        const val UPLOAD_INTERVAL_NS = 200_000_000L
        const val GLOW_DURATION_NS = 600_000_000L
        const val PAINT_PRIORITY = 4
        const val GLOW_PRIORITY = 5
        const val NEVER = Long.MIN_VALUE
    }
}
