@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue

/**
 * Per-demo memory of the last settled detent of the demo settings
 * [androidx.compose.material3.ModalBottomSheet] — issue #2084, the final open
 * sub-item of the bottom-sheet umbrella (#1154 Stage 3).
 *
 * Without this the sheet always re-opens at [SheetValue.PartiallyExpanded]. A
 * power user who, for a control-heavy demo (e.g. `LightingLabDemo`), always wants
 * the **fully-expanded** sheet had to re-drag it up on every single open. This
 * store records the detent the user last left the sheet at, **per demo**, so
 * each demo reopens its sheet exactly where it was last used.
 *
 * **Why SharedPreferences, not `rememberSaveable`.** `rememberSaveable` only
 * survives configuration changes and process death *while the saved-state
 * bundle is retained* — it is lost once the user navigates away from the demo
 * (the composable leaves composition) or the bundle is dropped. A genuinely
 * "sticky per-demo" preference must outlive the demo screen's lifecycle, so it
 * lives in a persistent settings store. The footprint is a couple of
 * single-byte ints — `SharedPreferences` is the right tool (DataStore would add
 * ~1.5 MB to the APK for no real gain — same call as [RecentSearchesStore]).
 *
 * **Per-demo key strategy (#2084 design decision — option b).** The umbrella's
 * hard rule is "38 demo call-sites × 0 call-site changes", so we cannot thread
 * an explicit `demoId` through [DemoScaffold]. Instead the key is derived from
 * the `title: String` already passed to every [DemoScaffold] call — call sites
 * stay byte-identical and the public signature is unchanged. Demo titles are
 * unique (each is a distinct human-readable screen name), so a title is a
 * stable per-demo key. The title is namespaced under a `detent.` prefix inside
 * a dedicated prefs file so it can never collide with another setting.
 */
object DemoSheetDetentStore {
    private const val PREFS = "sceneview_demo_sheet_detent"
    private const val KEY_PREFIX = "detent."

    // SheetValue is not Parcelable/persistable directly; map to a stable int.
    private const val PARTIALLY_EXPANDED = 0
    private const val EXPANDED = 1

    /**
     * The detent the demo titled [demoTitle] should reopen its settings sheet
     * at. [SheetValue.PartiallyExpanded] for a demo never seen before (the
     * unchanged default behaviour) — [SheetValue.Expanded] only once the user
     * has settled the sheet there at least once.
     */
    fun lastDetent(context: Context, demoTitle: String): SheetValue =
        when (prefs(context).getInt(KEY_PREFIX + demoTitle, PARTIALLY_EXPANDED)) {
            EXPANDED -> SheetValue.Expanded
            else -> SheetValue.PartiallyExpanded
        }

    /**
     * Persist [detent] as the last settled detent for the demo titled
     * [demoTitle]. [SheetValue.Hidden] is ignored — only the two *shown*
     * detents are meaningful to restore; a dismissed sheet must still reopen at
     * whichever shown detent the user last left it.
     */
    fun setLastDetent(context: Context, demoTitle: String, detent: SheetValue) {
        val encoded = when (detent) {
            SheetValue.Expanded -> EXPANDED
            SheetValue.PartiallyExpanded -> PARTIALLY_EXPANDED
            SheetValue.Hidden -> return
        }
        prefs(context).edit().putInt(KEY_PREFIX + demoTitle, encoded).apply()
    }

    // applicationContext — prefs access must never pin an Activity context.
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
