package io.github.sceneview.web

import io.github.sceneview.web.bindings.Engine
import io.github.sceneview.web.bindings.filmicColorGrading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorGradingTest {
    @Test
    fun filmicPreservesChromaByScalingLuminance() {
        val filament = js("Filament")
        val oldGrading = filament.ColorGrading
        val oldMapping = filament["ColorGrading\$ToneMapping"]
        val calls = js("({})")
        val builder = js("({})")
        builder.toneMapping = { value: dynamic -> calls.mapping = value; builder }
        builder.luminanceScaling = { value: Boolean -> calls.scaling = value; builder }
        builder.build = { engine: dynamic -> calls.engine = engine; calls }
        filament.ColorGrading = js("({})")
        filament.ColorGrading.Builder = { builder }
        filament["ColorGrading\$ToneMapping"] = js("({ FILMIC: 3 })")
        try {
            val engine = js("({})").unsafeCast<Engine>()
            assertTrue(filmicColorGrading(engine) === calls)
            assertEquals(3, calls.mapping)
            assertEquals(true, calls.scaling, "per-channel Filmic washes out saturated materials")
            assertTrue(calls.engine === engine)
        } finally {
            filament.ColorGrading = oldGrading
            filament["ColorGrading\$ToneMapping"] = oldMapping
        }
    }
}
