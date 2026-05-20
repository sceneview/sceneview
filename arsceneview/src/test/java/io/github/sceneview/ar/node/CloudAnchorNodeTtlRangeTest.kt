package io.github.sceneview.ar.node

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [CloudAnchorNode.TTL_DAYS_RANGE].
 *
 * The full `host()` path requires a live ARCore `Session` + Filament `Engine` (JNI), but the
 * `ttlDays` validation lives in a [kotlin.require] block executed BEFORE the ARCore call, so the
 * range constant itself is the public contract. Pin it here so any future widening/narrowing of
 * the allowed range is intentional (and the corresponding [io.github.sceneview.ar.CloudAnchorEntry]
 * validation stays in lockstep).
 *
 * See [#1734](https://github.com/sceneview/sceneview/issues/1734).
 */
class CloudAnchorNodeTtlRangeTest {

    @Test
    fun `TTL_DAYS_RANGE pins the ARCore 1 to 365 day persistence limit`() {
        // ARCore's hostCloudAnchorAsync rejects ttlDays outside this range — pin so a future
        // refactor doesn't silently widen the contract.
        assertEquals(1..365, CloudAnchorNode.TTL_DAYS_RANGE)
    }
}
