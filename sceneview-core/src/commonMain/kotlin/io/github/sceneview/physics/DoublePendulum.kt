/*
 * Adapted from twolinks (https://github.com/radcli14/twolinks) and modified by the
 * SceneView contributors. The notice of the upstream project follows.
 *
 * MIT License
 *
 * Copyright (c) 2022 radcli14
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.github.sceneview.physics

import io.github.sceneview.math.Position
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure, platform-independent **double-pendulum** (two-link) physics.
 *
 * Adapted from [@radcli14](https://github.com/radcli14)'s MIT-licensed
 * [`twolinks`](https://github.com/radcli14/twolinks) `TwoLinks.kt` — the Lagrangian
 * equations of motion for two rigid links hinged in series, with the second link's
 * pivot fixed at the tip of the first. Where `twolinks` uses RK4 and a parametrised
 * mass matrix tuned for its UI sliders, this port keeps the same point-mass physics
 * but exposes a simpler, dependency-free API matching [PhysicsSimulation] conventions
 * (immutable [DoublePendulumState], pure `step` function, no platform imports).
 *
 * ## Model
 *
 * Each link is an ideal rigid rod of [DoublePendulumLink.length] with its whole
 * [DoublePendulumLink.mass] concentrated at its **tip** (the classic textbook double
 * pendulum). [DoublePendulumLink.angle] is measured in **radians from the downward
 * vertical** — `0` is straight down, positive rotates counter-clockwise in the XY
 * plane (the plane the pendulum swings in). This is a chaotic system: tiny changes
 * in the initial state diverge exponentially.
 *
 * ## Integrator
 *
 * Semi-implicit (symplectic) Euler — the same family `twolinks` uses. Velocities are
 * updated from the analytic angular accelerations first, then positions from the new
 * velocities. Symplectic Euler has bounded energy error over long runs (unlike
 * explicit Euler, which injects energy and explodes), which is what makes the
 * energy-conservation test pass with `damping = 0`.
 *
 * ## Coordinate system
 *
 * Physics runs in 2D (the XY swing plane); +Y is up, gravity pulls toward -Y. Joint
 * positions are exposed as 3D [Position]s with `z = 0` so a renderer can place them
 * directly. [pivot] is the fixed hinge of the first link.
 *
 * ```
 * val pendulum = DoublePendulum(
 *     link1 = DoublePendulumLink(length = 0.5f, mass = 1f, angle = HALF_PI),
 *     link2 = DoublePendulumLink(length = 0.5f, mass = 1f, angle = HALF_PI),
 * )
 * // each frame:
 * pendulum = pendulum.step(deltaSeconds)
 * renderLink(from = pendulum.pivot,        to = pendulum.joint)   // first link
 * renderLink(from = pendulum.joint,        to = pendulum.tip)     // second link
 * ```
 */

/** Quarter turn in radians — convenient initial [DoublePendulumLink.angle] (horizontal). */
const val HALF_PI: Float = (PI / 2.0).toFloat()

/** Standard gravitational acceleration in m/s² (magnitude — direction is -Y). */
const val DEFAULT_GRAVITY: Float = 9.8f

/**
 * One rigid link of a [DoublePendulum].
 *
 * @param length Rod length in meters. Must be > 0.
 * @param mass Point mass (kg) concentrated at the rod tip. Must be > 0.
 * @param angle Current angle in **radians** measured from the downward vertical
 *   (`0` = hanging straight down, positive = counter-clockwise).
 * @param angularVelocity Current angular velocity in radians per second.
 */
data class DoublePendulumLink(
    val length: Float = 0.5f,
    val mass: Float = 1f,
    val angle: Float = HALF_PI,
    val angularVelocity: Float = 0f,
)

/**
 * Immutable state of a [DoublePendulum] simulation.
 *
 * @param link1 The upper link, hinged at [pivot].
 * @param link2 The lower link, hinged at the tip of [link1].
 * @param pivot Fixed world-space hinge position of [link1] (defaults to the origin).
 * @param gravity Gravitational acceleration magnitude in m/s² (pulls toward -Y).
 * @param damping Per-second velocity decay in `[0..1]`. `0` conserves energy; small
 *   positive values (e.g. `0.02`) make the pendulum gradually come to rest.
 */
data class DoublePendulumState(
    val link1: DoublePendulumLink = DoublePendulumLink(),
    val link2: DoublePendulumLink = DoublePendulumLink(),
    val pivot: Position = Position(),
    val gravity: Float = DEFAULT_GRAVITY,
    val damping: Float = 0f,
) {
    /** World-space position of the joint between [link1] and [link2] (tip of link 1). */
    val joint: Position
        get() = Position(
            x = pivot.x + link1.length * sin(link1.angle),
            y = pivot.y - link1.length * cos(link1.angle),
            z = pivot.z,
        )

    /** World-space position of the free tip of [link2]. */
    val tip: Position
        get() {
            val j = joint
            return Position(
                x = j.x + link2.length * sin(link2.angle),
                y = j.y - link2.length * cos(link2.angle),
                z = j.z,
            )
        }

    /**
     * Total mechanical energy (kinetic + potential) of the system in joules.
     *
     * Used by the energy-conservation test: with [damping] `== 0` this stays
     * roughly constant across many [DoublePendulum.step] calls. Potential energy
     * is measured relative to [pivot]'s Y.
     */
    val totalEnergy: Float
        get() {
            val l1 = link1.length
            val l2 = link2.length
            val m1 = link1.mass
            val m2 = link2.mass
            val w1 = link1.angularVelocity
            val w2 = link2.angularVelocity
            // Tip velocities (planar). v1 = l1*w1; v2 combines both link rates.
            val v1Sq = (l1 * w1) * (l1 * w1)
            // Second tip velocity via the law of cosines on the two rotating segments.
            val v2Sq = (l1 * w1) * (l1 * w1) +
                (l2 * w2) * (l2 * w2) +
                2f * l1 * l2 * w1 * w2 * cos(link1.angle - link2.angle)
            val kinetic = 0.5f * m1 * v1Sq + 0.5f * m2 * v2Sq
            // Heights of the two masses relative to the pivot (negative below it).
            val y1 = -l1 * cos(link1.angle)
            val y2 = y1 - l2 * cos(link2.angle)
            val potential = m1 * gravity * y1 + m2 * gravity * y2
            return kinetic + potential
        }
}

/**
 * Stateless double-pendulum integrator.
 *
 * Holds no mutable state itself — call [step] with the current [DoublePendulumState]
 * to obtain the next one, mirroring [simulateStep]'s pure-function style. Construct
 * one and keep advancing the [state] each frame:
 *
 * ```
 * var state = DoublePendulumState(/* … */)
 * // frame loop:
 * state = DoublePendulum.step(state, deltaSeconds)
 * ```
 */
object DoublePendulum {

    /**
     * Largest time-step a single integration sub-step uses.
     *
     * A double pendulum is chaotic, so symplectic Euler needs a fine `dt` to keep
     * its energy band tight — a raw 60 Hz frame (`1/60 s`) drifts visibly, whereas
     * `1/240 s` keeps energy bounded over long runs. [step] therefore sub-steps
     * every real frame down to this resolution. Sub-steps are pure arithmetic, so
     * the 4× sub-stepping is negligible cost.
     */
    const val MAX_TIME_STEP: Float = 1f / 240f

    /**
     * Advance the double-pendulum simulation by [deltaSeconds].
     *
     * Pure function — no mutation, no side effects. Negative [deltaSeconds] is
     * ignored; longer real frames are sub-stepped at [MAX_TIME_STEP] resolution so
     * the chaotic system stays numerically stable regardless of frame rate.
     *
     * @param state Current simulation state.
     * @param deltaSeconds Elapsed wall-clock time since the last step, in seconds.
     * @return The simulation state after advancing by [deltaSeconds].
     */
    fun step(state: DoublePendulumState, deltaSeconds: Float): DoublePendulumState {
        if (deltaSeconds <= 0f) return state
        // Sub-step so a slow 30 fps frame still integrates with a 60 Hz-stable dt.
        var remaining = deltaSeconds.coerceAtMost(1f) // never simulate >1s in one call
        var current = state
        while (remaining > 0f) {
            val dt = remaining.coerceAtMost(MAX_TIME_STEP)
            current = integrate(current, dt)
            remaining -= dt
        }
        return current
    }

    /**
     * One symplectic-Euler integration sub-step of fixed [dt].
     *
     * Computes the analytic angular accelerations of both links from the Lagrangian
     * equations of motion of a point-mass double pendulum, updates the angular
     * velocities (with [DoublePendulumState.damping] applied), then advances the
     * angles from the *new* velocities.
     */
    private fun integrate(state: DoublePendulumState, dt: Float): DoublePendulumState {
        val l1 = state.link1.length
        val l2 = state.link2.length
        val m1 = state.link1.mass
        val m2 = state.link2.mass
        val a1 = state.link1.angle
        val a2 = state.link2.angle
        val w1 = state.link1.angularVelocity
        val w2 = state.link2.angularVelocity
        val g = state.gravity

        val delta = a1 - a2
        val cosD = cos(delta)
        val sinD = sin(delta)

        // Standard point-mass double-pendulum angular accelerations.
        // (See e.g. https://en.wikipedia.org/wiki/Double_pendulum — Lagrangian form.)
        val den1 = l1 * (2f * m1 + m2 - m2 * cos(2f * delta))
        val den2 = l2 * (2f * m1 + m2 - m2 * cos(2f * delta))

        // Guard against the degenerate zero-length / zero-mass case.
        if (den1 == 0f || den2 == 0f) return state

        val acc1 = (
            -g * (2f * m1 + m2) * sin(a1) -
                m2 * g * sin(a1 - 2f * a2) -
                2f * sinD * m2 * (w2 * w2 * l2 + w1 * w1 * l1 * cosD)
            ) / den1

        val acc2 = (
            2f * sinD * (
                w1 * w1 * l1 * (m1 + m2) +
                    g * (m1 + m2) * cos(a1) +
                    w2 * w2 * l2 * m2 * cosD
                )
            ) / den2

        // Symplectic Euler: update velocities first…
        val dampFactor = (1f - state.damping * dt).coerceIn(0f, 1f)
        val newW1 = (w1 + acc1 * dt) * dampFactor
        val newW2 = (w2 + acc2 * dt) * dampFactor

        // …then advance the angles from the new velocities.
        val newA1 = a1 + newW1 * dt
        val newA2 = a2 + newW2 * dt

        return state.copy(
            link1 = state.link1.copy(angle = newA1, angularVelocity = newW1),
            link2 = state.link2.copy(angle = newA2, angularVelocity = newW2),
        )
    }
}
