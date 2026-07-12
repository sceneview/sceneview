package io.github.sceneview.core.splat

/**
 * A decoded 3D Gaussian Splatting point cloud, in a portable, renderer-agnostic form.
 *
 * This is the shared parsing output of [SplatParser]. It lives in `sceneview-core` (Kotlin
 * Multiplatform, `commonMain`) so that every renderer backend — Android/Filament, Apple/RealityKit,
 * Web/Filament.js — consumes the exact same decoded data. It contains **no** rendering, GPU, or
 * platform types; only primitive arrays.
 *
 * All per-splat attributes are stored as flat [FloatArray]s in splat order (splat `i` occupies
 * indices `i*stride .. i*stride+stride-1`). Every value has already had its activation function
 * applied, so a renderer can upload these arrays directly with no further math:
 *
 * - [positions] — world-space center `x, y, z` (stride 3). Raw, as stored by the trainer.
 * - [scales] — per-axis Gaussian scale `x, y, z` (stride 3), **linear** (the `exp()` of the
 *   log-scales that PLY/SPZ store has already been applied).
 * - [rotations] — orientation quaternion `x, y, z, w` (stride 4), **normalized** to unit length.
 *   Note the scalar-last `xyzw` order: INRIA PLY files store `w, x, y, z` (scalar-first) and this
 *   parser reorders them to `xyzw` here.
 * - [colors] — view-independent base color `r, g, b` (stride 3) as **linear RGB clamped to `0..1`**.
 *   The SH degree-0 DC term is converted with `0.5 + SH_C0 * dc` (`SH_C0 = 0.28209479177387814`).
 *   Higher-order spherical-harmonics bands are ignored in this P1 parser.
 * - [opacities] — per-splat alpha `a` (stride 1) in `0..1`, with the logistic `sigmoid()` already
 *   applied.
 *
 * @property count number of splats; each `*Array` length is `count * stride`.
 */
class SplatCloud(
    val count: Int,
    val positions: FloatArray,
    val scales: FloatArray,
    val rotations: FloatArray,
    val colors: FloatArray,
    val opacities: FloatArray,
) {
    init {
        require(count >= 0) { "count must be >= 0, was $count" }
        require(positions.size == count * 3) { "positions must be count*3 (${count * 3}), was ${positions.size}" }
        require(scales.size == count * 3) { "scales must be count*3 (${count * 3}), was ${scales.size}" }
        require(rotations.size == count * 4) { "rotations must be count*4 (${count * 4}), was ${rotations.size}" }
        require(colors.size == count * 3) { "colors must be count*3 (${count * 3}), was ${colors.size}" }
        require(opacities.size == count) { "opacities must be count ($count), was ${opacities.size}" }
    }
}
