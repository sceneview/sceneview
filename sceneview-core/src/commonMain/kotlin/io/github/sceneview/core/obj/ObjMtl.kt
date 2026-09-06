package io.github.sceneview.core.obj

internal object ObjMtl {
    fun read(bytes: ByteArray, materials: MutableMap<String, ObjMaterial>) {
        var factor: FloatArray? = null
        objLines(bytes) { tokens, line ->
            when (tokens.next()) {
                "newmtl" -> {
                    val name = tokens.rest()
                    if (name.isEmpty()) objError("MTL line $line: newmtl needs a name")
                    factor = defaultObjColor()
                    materials[name] = ObjMaterial(factor!!)
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

internal fun defaultObjColor(): FloatArray = floatArrayOf(0.62f, 0.64f, 0.68f, 1f)
