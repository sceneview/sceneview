package io.github.sceneview.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TEMPORARY mutation canary for #2733 — proves the newly-wired
 * `:sceneview-core:androidTest` gate actually turns the `unit-test`
 * CI job red. This commit is REVERTED on the same PR once the red
 * run is observed; it must never reach main.
 */
class MutationCanary2733Test {
    @Test
    fun deliberateFailure_provesTheGateBites() {
        assertEquals(1, 2, "Intentional #2733 mutation-canary failure — revert me")
    }
}
