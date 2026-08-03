/*
 * Copyright (C) 2017 Romain Guy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("NOTHING_TO_INLINE")

package io.github.erkko68.filament.utils

import kotlin.math.pow

/** π as a Float. */
const val FPI         = 3.1415926536f
/** π / 2 as a Float. */
const val HALF_PI     = FPI * 0.5f
/** 2π as a Float. */
const val TWO_PI      = FPI * 2.0f
/** 4π as a Float. */
const val FOUR_PI     = FPI * 4.0f
/** 1 / π as a Float. */
const val INV_PI      = 1.0f / FPI
/** 1 / (2π) as a Float. */
const val INV_TWO_PI  = INV_PI * 0.5f
/** 1 / (4π) as a Float. */
const val INV_FOUR_PI = INV_PI * 0.25f

/** 1.0 as a [Half]. */
val HALF_ONE = Half(0x3c00.toUShort())
/** 2.0 as a [Half]. */
val HALF_TWO = Half(0x4000.toUShort())

/**
 * Clamps [x] to the range [[min], [max]].
 *
 * @param x the value to clamp
 * @param min lower bound (inclusive)
 * @param max upper bound (inclusive)
 * @return [min] if x < min, [max] if x > max, otherwise x
 */
inline fun clamp(x: Float, min: Float, max: Float) = if (x < min) min else (if (x > max) max else x)

/**
 * Clamps [x] to the range [[min], [max]].
 *
 * @param x the [Half] value to clamp
 * @param min lower bound (inclusive)
 * @param max upper bound (inclusive)
 * @return [min] if x < min, [max] if x > max, otherwise x
 */
inline fun clamp(x: Half, min: Half, max: Half) = if (x < min) min else (if (x > max) max else x)

/**
 * Clamps [x] to the range [[min], [max]].
 *
 * @param x the Int value to clamp
 * @param min lower bound (inclusive)
 * @param max upper bound (inclusive)
 * @return [min] if x < min, [max] if x > max, otherwise x
 */
inline fun clamp(x: Int, min: Int, max: Int) = if (x < min) min else (if (x > max) max else x)

/**
 * Clamps [x] to [0.0, 1.0].
 *
 * @param x the value to saturate
 * @return x clamped to [0.0, 1.0]
 */
inline fun saturate(x: Float) = clamp(x, 0.0f, 1.0f)

/**
 * Clamps [x] to [0.0, 1.0] as a [Half].
 *
 * @param x the [Half] value to saturate
 * @return x clamped to [[Half.POSITIVE_ZERO], [HALF_ONE]]
 */
inline fun saturate(x: Half) = clamp(x, Half.POSITIVE_ZERO, HALF_ONE)

/**
 * Linearly interpolates between [a] and [b] by factor [x]: `a * (1 - x) + b * x`.
 *
 * @param a the start value
 * @param b the end value
 * @param x the interpolation factor
 * @return the interpolated value
 */
inline fun mix(a: Float, b: Float, x: Float) = a * (1.0f - x) + b * x

/**
 * Linearly interpolates between [a] and [b] by factor [x] using [Half] arithmetic.
 *
 * @param a the start value
 * @param b the end value
 * @param x the interpolation factor
 * @return the interpolated [Half] value
 */
inline fun mix(a: Half, b: Half, x: Half) = a * (HALF_ONE - x) + b * x

/**
 * Converts radians to degrees.
 *
 * @param v angle in radians
 * @return angle in degrees
 */
inline fun degrees(v: Float) = v * (180.0f * INV_PI)

/**
 * Converts degrees to radians.
 *
 * @param v angle in degrees
 * @return angle in radians
 */
inline fun radians(v: Float) = v * (FPI / 180.0f)

/**
 * Returns the fractional part of [v].
 *
 * @param v the input value
 * @return `v % 1`
 */
inline fun fract(v: Float) = v % 1

/**
 * Returns [v] squared.
 *
 * @param v the input value
 * @return v * v
 */
inline fun sqr(v: Float) = v * v

/**
 * Returns [v] squared as a [Half].
 *
 * @param v the input [Half] value
 * @return v * v
 */
inline fun sqr(v: Half) = v * v

/**
 * Raises [x] to the power [y].
 *
 * @param x the base
 * @param y the exponent
 * @return x raised to the power y
 */
inline fun pow(x: Float, y: Float) = (x.toDouble().pow(y.toDouble())).toFloat()
