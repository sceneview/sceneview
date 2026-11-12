package io.github.sceneview.loaders

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sceneview.material.materialOrNull
import io.github.sceneview.render.RenderTestHarness
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Native regression: before the fix, createModel aborts rather than throwing an exception. */
@RunWith(AndroidJUnit4::class)
class VolumeMaterialLoadTest {
    @Test
    fun volume_loads_and_transmission_material_is_still_recorded() {
        val harness = RenderTestHarness(width = 64, height = 64)
        try {
            harness.runOnMain {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val loader = ModelLoader(harness.engine, context)
                try {
                    val model = loader.createModel(triangle())
                    assertNotNull(model)
                    val material = model.instance.materialInstances.single().materialOrNull
                    assertNotNull("RecordingMaterialProvider must still record the material", material)
                    assertTrue("Transmission must survive", material!!.hasParameter("transmissionFactor"))
                    // Exercise the instanced entry point as well.
                    assertTrue(loader.createInstancedModel(triangle(), 2).isNotEmpty())
                } finally {
                    loader.destroy()
                }
            }
        } finally {
            harness.destroy()
        }
    }

    private fun triangle(): ByteBuffer {
        val positions = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (v in floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)) putFloat(v)
        }.array()
        val data = Base64.encodeToString(positions, Base64.NO_WRAP)
        val json = """
            {"asset":{"version":"2.0"},
             "extensionsUsed":["KHR_materials_volume","KHR_materials_transmission",
               "KHR_materials_iridescence","KHR_materials_specular","KHR_materials_dispersion"],
             "extensionsRequired":["KHR_materials_volume"],
             "buffers":[{"byteLength":36,"uri":"data:application/octet-stream;base64,$data"}],
             "bufferViews":[{"buffer":0,"byteLength":36}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3",
               "min":[0,0,0],"max":[1,1,0]}],
             "materials":[{"extensions":{
               "KHR_materials_volume":{"thicknessFactor":0.5,"attenuationDistance":1.0},
               "KHR_materials_transmission":{"transmissionFactor":0.9},
               "KHR_materials_iridescence":{"iridescenceFactor":1.0},
               "KHR_materials_specular":{"specularFactor":0.8},
               "KHR_materials_dispersion":{"dispersion":0.2}}}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0},"material":0}]}],
             "nodes":[{"mesh":0}],"scenes":[{"nodes":[0]}],"scene":0}
        """.trimIndent().toByteArray()
        return ByteBuffer.allocateDirect(json.size).put(json).apply { flip() }
    }
}
