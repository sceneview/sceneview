package io.github.sceneview.core.obj

import io.github.sceneview.core.threemf.ThreeMfGlb

internal object ObjMtl {
    fun read(bytes: ByteArray, materials: MutableMap<String, ObjMaterial>) {
        var factor: FloatArray? = null
        objLines(bytes) { tokens, line ->
            when (tokens.next()) {
                "newmtl" -> {
                    val name = tokens.rest()
                    if (name.isEmpty()) objError("MTL line $line: newmtl needs a name")
                    val color = defaultObjColor()
                    factor = color
                    materials[name] = ObjMaterial(color)
                }
                "Kd" -> factor?.let { rgba ->
                    repeat(3) { rgba[it] = tokens.number(line).coerceIn(0f, 1f) }
                }
                "d" -> factor?.let {
                    val token = tokens.next()
                    it[3] = (if (token == "-halo") tokens.number(line) else number(token, line)).coerceIn(0f, 1f)
                }
                "Tr" -> factor?.let { it[3] = 1f - tokens.number(line).coerceIn(0f, 1f) }
                "map_Kd" -> println("SceneView OBJ: map_Kd textures not yet supported; skipping texture")
            }
        }
    }
}

/**
 * The shared neutral fallback, in linear space — identical to the one the 3MF, STL and PLY paths
 * use, so a colourless file reads the same grey whatever format it arrived in (#3548).
 */
internal fun defaultObjColor(): FloatArray = ThreeMfGlb.defaultBaseColor()
