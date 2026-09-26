package io.github.sceneview.demo.common.placement

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Only a successful, supported rendering change changes the comparison's visible state. */
internal class FeatureComparisonControl(initiallyEnabled: Boolean) {
    var supported: Boolean? by mutableStateOf(null)
        private set
    var enabled: Boolean by mutableStateOf(initiallyEnabled)
        private set

    fun confirmSupport(value: Boolean) { supported = value }

    fun toggle(apply: (Boolean) -> Boolean): Boolean {
        if (supported != true) return false
        val next = !enabled
        if (!apply(next)) return false
        enabled = next
        return true
    }
}
