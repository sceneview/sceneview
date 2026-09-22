package io.github.sceneview.demo.adk

import com.google.adk.kt.annotations.Tool

object AdkProbe {
    @Tool
    fun probe(): String = "ADK for Kotlin is available"
}
