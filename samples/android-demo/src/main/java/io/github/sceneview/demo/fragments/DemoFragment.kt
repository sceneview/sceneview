package io.github.sceneview.demo.fragments

import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoEntry

/**
 * Append-only demo registration.
 *
 * Each demo in `samples/android-demo` is registered by **one Kotlin object**
 * implementing this interface, declared in **one file** under
 * `samples/android-demo/src/main/java/io/github/sceneview/demo/fragments/<Id>Fragment.kt`.
 *
 * The
 * [`samples/android-demo/scripts/collate-demos.sh`](../../../../../../../../scripts/collate-demos.sh)
 * collator script reads every `*Fragment.kt` in this package and regenerates
 * [GeneratedDemos] so the central registry ([io.github.sceneview.demo.ALL_DEMOS])
 * and the deep-link router ([io.github.sceneview.demo.DemoRouter]) pick up new
 * demos automatically. The same collator also rewrites the "Sample app demos
 * (Android)" section of `llms.txt` (and its mirror `docs/docs/llms.txt`)
 * between marker comments so the demo bullet list stays in sync with the
 * fragments without any hand-editing (#1871) — **no shared file edits.**
 *
 * Adding a new demo:
 *  1. Drop one new file: `fragments/<Id>Fragment.kt` (see existing fragments
 *     for the pattern).
 *  2. Run `samples/android-demo/scripts/collate-demos.sh` (or the
 *     `:samples:android-demo:collateDemos` Gradle task).
 *  3. Commit the new fragment, the regenerated [GeneratedDemos], the updated
 *     `llms.txt` + `docs/docs/llms.txt`, and the regenerated
 *     `mcp/src/generated/llms-txt.ts` bundle.
 *
 * That's it. No edits to `DemoRegistry.kt` or `MainActivity.kt`.
 *
 * See `samples/android-demo/src/main/java/io/github/sceneview/demo/fragments/README.md`
 * for the full how-to and rationale (issue #1797).
 */
interface DemoFragment {
    /**
     * Metadata: id, title, subtitle, category, icon, status.
     *
     * The [DemoEntry.id] is what `sceneview://demo/<id>` deep links route to,
     * and what the collator uses to sort the generated `ALL_DEMOS` list for a
     * stable, conflict-free diff.
     */
    val entry: DemoEntry

    /**
     * The composable shown when the demo is opened — usually a one-line wrapper
     * around the existing `demos.<Id>Demo(onBack)` composable. Kept as a
     * separate function so fragments stay declarative (metadata only) and
     * the actual demo composables live where they always have, under
     * `samples/android-demo/.../demos/`.
     */
    @Composable
    fun Screen(onBack: () -> Unit)
}
