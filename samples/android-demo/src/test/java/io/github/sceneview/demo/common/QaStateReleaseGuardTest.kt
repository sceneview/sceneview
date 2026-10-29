package io.github.sceneview.demo.common

import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DeepLinkRouter
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.ai.ASK_QA_STATE_IDS
import io.github.sceneview.demo.demos.internal.CloudAnchorScenario
import io.github.sceneview.demo.demos.internal.cloudAnchorScenarioOf
import io.github.sceneview.demo.ui.home.HERO_STILL_QA_STATE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `--ez qa_mode true --es qa_state <id>` must not pin a release build to a fake state (#4555).
 *
 * `qa_mode` is an intent extra any app on the phone can send to the exported launcher activity,
 * so the store build only lets it freeze animations: [DeepLinkRouter.resolveQaState] and
 * [qaStateOverridesAllowed] both answer "no" unless the build is a debug one. The explicit
 * `debugBuild` cases pin both answers on either variant; the `BuildConfig.DEBUG` cases run as
 * they are under `testDebugUnitTest` and `testReleaseUnitTest`, which CI both runs, so each
 * variant proves its own answer.
 */
@RunWith(RobolectricTestRunner::class)
class QaStateReleaseGuardTest {

    /**
     * The ids of the three vocabularies the issue names: Cloud Anchors (as Maestro sends them,
     * `resolve_not_found`), Point & Ask, Home. The router knows no vocabulary, so the other
     * demos' ids (Geospatial, Rerun, Record & Playback, motion sensor) take the same path.
     */
    private val everyQaStateId: List<String> =
        CloudAnchorScenario.entries.map { it.name.toSnakeCase() } + ASK_QA_STATE_IDS + HERO_STILL_QA_STATE

    private fun String.toSnakeCase(): String =
        replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

    @After
    fun resetQaMode() {
        DemoSettings.qaMode = false
    }

    @Test
    fun `a release build drops every qa_state id even in QA mode`() {
        for (id in everyQaStateId) {
            assertNull(id, DeepLinkRouter.resolveQaState(qaMode = true, raw = id, debugBuild = false))
        }
    }

    @Test
    fun `a debug build in QA mode still pins every qa_state id`() {
        for (id in everyQaStateId) {
            assertEquals(id, DeepLinkRouter.resolveQaState(qaMode = true, raw = id, debugBuild = true))
        }
    }

    @Test
    fun `a debug build outside QA mode drops the id`() {
        assertNull(DeepLinkRouter.resolveQaState(qaMode = false, raw = "hosted", debugBuild = true))
    }

    @Test
    fun `overrides are allowed only in a debug build with QA mode on`() {
        assertTrue(qaStateOverridesAllowed(qaMode = true, debugBuild = true))
        assertFalse(qaStateOverridesAllowed(qaMode = true, debugBuild = false))
        assertFalse(qaStateOverridesAllowed(qaMode = false, debugBuild = true))
        assertFalse(qaStateOverridesAllowed(qaMode = false, debugBuild = false))
    }

    @Test
    fun `the snake_case ids still name their Cloud Anchors scenario`() {
        for (scenario in CloudAnchorScenario.entries) {
            assertEquals(scenario, cloudAnchorScenarioOf(scenario.name.toSnakeCase()))
        }
    }

    @Test
    fun `this variant answers by its own BuildConfig`() {
        DemoSettings.qaMode = true
        for (id in everyQaStateId) {
            val pinned = DeepLinkRouter.resolveQaState(DemoSettings.qaMode, id)
            assertEquals(id, if (BuildConfig.DEBUG) id else null, pinned)
        }
        assertEquals(BuildConfig.DEBUG, qaStateOverridesAllowed())
    }
}
