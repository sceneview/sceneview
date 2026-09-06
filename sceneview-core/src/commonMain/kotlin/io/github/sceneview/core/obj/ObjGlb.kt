package io.github.sceneview.core.obj

import io.github.sceneview.core.threemf.IntArrayBuilder
import io.github.sceneview.core.threemf.ThreeMfGlb

/** Reuse 3MF's accessors, materials, JSON escaping and aligned container assembly without its rotation. */
internal object ObjGlb {
    fun encode(model: ObjModel): ByteArray {
        if (model.groups.isEmpty()) objError("OBJ contains no groups")
        val builder = ThreeMfGlb.GltfBuilder("SceneView OBJ loader")
        val materialIndices = HashMap<List<Float>, Int>()
        for (group in model.groups) {
            validate(group)
            val trianglesByMaterial = LinkedHashMap<String?, IntArrayBuilder>()
            for (triangle in 0 until group.triangleCount) {
                trianglesByMaterial.getOrPut(group.triangleMaterials[triangle]) { IntArrayBuilder() }.add(triangle)
            }
            val primitives = trianglesByMaterial.map { (name, triangles) ->
                val color = model.materials[name]?.baseColorFactor ?: defaultObjColor()
                if (color.size != 4 || color.any { !it.isFinite() || it !in 0f..1f }) {
                    objError("OBJ material '$name': expected four finite RGBA factors in [0, 1]")
                }
                val material = materialIndices.getOrPut(color.toList()) { builder.addMaterial(color) }
                val selected = triangles.toArray()
                builder.addPrimitive(
                    select(group.positions, selected, 9),
                    select(group.normals, selected, 9),
                    // OBJ's v origin is bottom-left; glTF's texture origin is top-left.
                    group.textureCoordinates?.let { uv ->
                        select(uv, selected, 6).also { values ->
                            for (i in 1 until values.size step 2) values[i] = 1f - values[i]
                        }
                    },
                    material
                )
            }
            builder.addMesh(group.name, primitives)
        }
        builder.addScaledRoot("obj", model.unit.meters)
        return builder.toGlb()
    }

    private fun select(values: FloatArray, triangles: IntArray, stride: Int): FloatArray =
        FloatArray(triangles.size * stride).also { output ->
            triangles.forEachIndexed { index, triangle ->
                values.copyInto(output, index * stride, triangle * stride, (triangle + 1) * stride)
            }
        }

    private fun validate(group: ObjGroup) {
        val positions = group.positions
        val uvs = group.textureCoordinates
        val sizesMatch = positions.isNotEmpty() && positions.size % 9 == 0 && group.normals.size == positions.size
        val countsMatch = group.triangleMaterials.size == group.triangleCount &&
            (uvs == null || uvs.size == group.triangleCount * 6)
        val finite = positions.all { it.isFinite() } && group.normals.all { it.isFinite() } &&
            (uvs == null || uvs.all { it.isFinite() })
        if (!sizesMatch || !countsMatch || !finite) {
            objError("OBJ group '${group.name}': inconsistent or non-finite triangle data")
        }
    }
}
