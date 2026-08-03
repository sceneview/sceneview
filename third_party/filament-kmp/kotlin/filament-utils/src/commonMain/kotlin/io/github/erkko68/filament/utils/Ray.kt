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

package io.github.erkko68.filament.utils

/**
 * A ray defined by an origin point and a direction vector.
 *
 * @property origin the starting point of the ray in world space
 * @property direction the direction of the ray; need not be normalized
 */
data class Ray(var origin: Float3 = Float3(), var direction: Float3)

/**
 * Returns the point along ray [r] at parameter [t]: `origin + direction * t`.
 *
 * @param r the ray
 * @param t the parameter along the ray
 * @return the world-space point at `r.origin + r.direction * t`
 */
fun pointAt(r: Ray, t: Float) = r.origin + r.direction * t
