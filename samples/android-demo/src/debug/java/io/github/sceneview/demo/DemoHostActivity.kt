package io.github.sceneview.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.fragments.GeneratedDemos
import io.github.sceneview.demo.theme.SceneViewDemoTheme

/**
 * Debug-only test harness activity.
 *
 * Launches a **single demo composable directly**, bypassing the home list + navigation stack.
 * Built only in debug builds so the shipped Play Store release APK isn't bloated with test
 * entry points.
 *
 * ### Why this exists
 *
 * The demo list uses a Compose `LazyColumn` whose scroll position is only partially visible
 * to `UiScrollable.scrollTextIntoView()` — items far down the list (e.g. `Physics`,
 * `Custom Mesh` in the ADVANCED section) stay out of the view tree until the LazyColumn
 * finalises recomposition after each swipe. This races with UiAutomator's "I'm done scrolling"
 * heuristic and manifests as `scrollTextIntoView` returning `false` even when enough scroll
 * budget remains.
 *
 * Rather than fight the scroll-until-visible loop, instrumentation tests can launch this
 * activity with an intent extra identifying the demo:
 *
 * ```kotlin
 * val intent = Intent(context, DemoHostActivity::class.java)
 *     .putExtra(EXTRA_DEMO_ID, "physics")
 *     .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
 * context.startActivity(intent)
 * ```
 *
 * The activity renders the demo composable directly, with `finish()` wired to the back button.
 * `DemoInteractionTest` uses this for every interaction test — fast, deterministic, scales
 * to all demos without any scroll-tuning.
 *
 * ### Routing is drift-proof
 *
 * The host **delegates to [GeneratedDemos.Screen]** — the collator-generated router that is
 * regenerated from every `*Fragment.kt` (#1797), so it covers **every** [ALL_DEMOS] id by
 * construction. Retired ids (e.g. `multi-model`, `text`, `gesture-editing`) are first resolved
 * to their live consolidated demo through [DeepLinkRouter.validate] / [DeepLinkRouter.DEMO_ID_ALIASES],
 * mirroring the main-app [DemoRouter] path. This replaced an earlier hand-written `when()` that
 * silently drifted from the catalog: three demos (#2319) and later seventeen AR demos were in the
 * catalog but missing from the `when()`, crashing this host with `Unknown demo id`. A pure-JVM
 * `testDebug` guard ([DemoHostRoutableTest]) now asserts [routableId] resolves every catalog id and
 * every alias.
 *
 * @see DemoEntry.id in [ALL_DEMOS] for the full list of valid ids.
 */
class DemoHostActivity : ComponentActivity() {

    companion object {
        const val EXTRA_DEMO_ID = "demo_id"

        /**
         * Resolves a raw demo [id] to the live, routable id this host will render, or `null`
         * if no demo (and no retired-id alias) matches.
         *
         * Pure, non-composable, no Android framework calls — so a JVM `testDebug` unit test can
         * assert the host routes every [ALL_DEMOS] id and every [DeepLinkRouter.DEMO_ID_ALIASES]
         * key without an emulator. A live registered id maps to itself; a retired id (e.g.
         * `multi-model`) maps to its consolidated successor (e.g. `model-viewer`); a registered
         * id with a matching generated fragment is what [GeneratedDemos.Screen] can render.
         *
         * The id is considered routable iff, after alias resolution, a [GeneratedDemos] fragment
         * registers it — which is exactly what [GeneratedDemos.Screen] checks at render time.
         */
        fun routableId(id: String?): String? {
            val resolved = DeepLinkRouter.validate(id) ?: return null
            return if (GeneratedDemos.all.any { it.id == resolved }) resolved else null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val demoId = intent.getStringExtra(EXTRA_DEMO_ID)
            ?: error("DemoHostActivity launched without $EXTRA_DEMO_ID extra")

        setContent {
            SceneViewDemoTheme {
                DemoById(demoId)
            }
        }
    }

    @Composable
    private fun DemoById(id: String) {
        val back: () -> Unit = { finish() }
        // Resolve retired ids to their live consolidated demo, then delegate to the
        // collator-generated router that covers every ALL_DEMOS id by construction. This
        // eliminates the hand-written-when() drift class (#2319 / #2320): there is no longer a
        // per-demo branch to forget.
        val resolved = routableId(id)
            ?: error(
                "Unknown demo id '$id' — not in ALL_DEMOS and not a known retired-id alias. " +
                    "Add a *Fragment.kt under io.github.sceneview.demo.fragments and run " +
                    "samples/android-demo/scripts/collate-demos.sh (see DemoHostRoutableTest).",
            )
        GeneratedDemos.Screen(id = resolved, onBack = back)
    }
}
