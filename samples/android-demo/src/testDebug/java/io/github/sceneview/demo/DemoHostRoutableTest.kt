package io.github.sceneview.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM guard that **every [ALL_DEMOS] id is routable by [DemoHostActivity]**.
 *
 * [DemoHostActivity] is the debug-only deep-link host that instrumentation tests
 * ([DemoInteractionTest], [DemoSmokeTest]) and the manual `--es demo_id <id>` QA channel use to
 * launch a single demo composable directly. Before #2320 it hand-maintained a `when (id)` mapping
 * ids to composables; a demo that was in the catalog ([ALL_DEMOS]) but missing a branch crashed the
 * harness with `error("Unknown demo id")`. [#2319](https://github.com/sceneview/sceneview/issues/2319)
 * found three such demos by interactive QA; an audit during #2320 found **seventeen** more AR demos
 * in the same state. Nothing prevented the regression from recurring on the next added demo.
 *
 * [DemoHostActivity] now delegates routing to the collator-generated
 * [io.github.sceneview.demo.fragments.GeneratedDemos.Screen], so it covers every catalog id by
 * construction, and exposes the pure, non-composable [DemoHostActivity.routableId] resolver this test
 * asserts against. The test lives in `src/testDebug` because [DemoHostActivity] is in the `debug`
 * source set; it runs in `:samples:android-demo:testDebugUnitTest` — **no emulator, no instrumentation**.
 *
 * If this test ever fails with "not routable", the fix is to add a `*Fragment.kt` under
 * `io.github.sceneview.demo.fragments` and run `samples/android-demo/scripts/collate-demos.sh` —
 * never to special-case the host.
 *
 * Closes [#2320](https://github.com/sceneview/sceneview/issues/2320). Sibling of
 * [DemoRegistryIntegrityTest] and [DeepLinkRouterTest].
 */
class DemoHostRoutableTest {

    @Test
    fun `every ALL_DEMOS id is routable by DemoHostActivity`() {
        // The core guard: no demo in the catalog may fall through to the host's
        // `error("Unknown demo id")` branch. routableId() mirrors what the activity does at
        // render time (alias-resolve, then delegate to GeneratedDemos.Screen) without touching
        // any Android framework or Compose runtime.
        val unrouteable = ALL_DEMOS.map { it.id }
            .filter { DemoHostActivity.routableId(it) == null }
        assertTrue(
            "These ALL_DEMOS ids are NOT routable by DemoHostActivity and would crash the " +
                "--es demo_id deep-link harness with \"Unknown demo id\". Add a *Fragment.kt and " +
                "run collate-demos.sh — never special-case the host: $unrouteable",
            unrouteable.isEmpty(),
        )
    }

    @Test
    fun `a registered demo id resolves to itself`() {
        // A live, registered id must route to exactly that id — not be rewritten by an alias.
        val sample = ALL_DEMOS.first().id
        assertEquals(sample, DemoHostActivity.routableId(sample))
    }

    @Test
    fun `every retired-id alias is routable`() {
        // Retired deep-link ids (e.g. `multi-model`, `text`, `gesture-editing`) must keep
        // resolving through the host, exactly as they do through the main-app DemoRouter — old
        // `sceneview://demo/<id>` links and QA scripts referencing the pre-consolidation ids
        // must not crash the host.
        val unrouteableAliases = DeepLinkRouter.DEMO_ID_ALIASES.keys
            .filter { DemoHostActivity.routableId(it) == null }
        assertTrue(
            "These retired-id aliases no longer route through DemoHostActivity (their " +
                "consolidation target may have been removed): $unrouteableAliases",
            unrouteableAliases.isEmpty(),
        )
    }

    @Test
    fun `an unknown demo id is not routable`() {
        // Negative-space guard: a string that is neither a registered id nor a known alias must
        // resolve to null (the host then errors loudly) — so the guard genuinely guards and does
        // not vacuously pass by routing everything.
        assertNull(DemoHostActivity.routableId("definitely-not-a-real-demo"))
        assertNull(DemoHostActivity.routableId(null))
        assertNull(DemoHostActivity.routableId(""))
    }
}
