package io.github.sceneview.demo.adk

import com.google.adk.kt.annotations.Tool

/**
 * Build probe for ADK for Kotlin: proves the KSP processor runs against the demo's Kotlin
 * pin. Not wired to any UI yet.
 */
object AdkProbe {
    const val PROBE_MESSAGE = "ADK for Kotlin is available"

    @Tool
    fun probe(): String = PROBE_MESSAGE
}
