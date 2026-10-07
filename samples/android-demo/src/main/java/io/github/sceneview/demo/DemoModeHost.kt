package io.github.sceneview.demo

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Surface
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.sceneview.demo.telemetry.LocalSampleId
import io.github.sceneview.demo.telemetry.logSampleModeChange
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.ConnectedChoiceRow

/**
 * One mode of a consolidated card (samples step 0): a demo that used to be a card of its own
 * and now lives under another card's id.
 *
 * [key] is the lowercase token used three ways — the `?tab=<key>` deep-link name
 * ([DeepLinkRouter.TAB_NAMES]), the `sample_interaction` control (`mode_<key>`) and the pill's
 * test tag (`demo_mode_<key>`) — so the three never drift apart.
 */
@Immutable
data class DemoMode(val key: String, @StringRes val labelRes: Int)

/**
 * The mode switch a [DemoModeHost] hands to the [DemoScaffold] of whichever demo it is
 * showing. The scaffold draws it as the shared mode pill at the foot of its bottom band, just
 * above the dock, so every consolidated card switches modes in the same place.
 *
 * [inSheet] — the host folded the switch away (#4379): the scaffold draws no pill over the
 * scene and heads its settings sheet with the modes instead ([DemoModeRow]).
 */
@Immutable
class DemoModeSwitch internal constructor(
    val modes: List<DemoMode>,
    val selected: Int,
    val onSelect: (Int) -> Unit,
    val inSheet: Boolean = false,
)

/** The switch of the [DemoModeHost] around the current demo, or `null` outside one. */
val LocalDemoModeSwitch = compositionLocalOf<DemoModeSwitch?> { null }

/**
 * Hosts the modes of a consolidated card: [content] is called with the selected mode index and
 * shows that mode's demo, keyed so switching disposes the previous demo — its scene, its AR
 * session — before the next one starts.
 *
 * **Launch tab.** A mode can be the target of a deep link (`?tab=pendulum`, or a retired id
 * such as `double-pendulum` through [DeepLinkRouter.ALIAS_INITIAL_TAB]). [tabToMode] names the
 * launch tabs the host owns and the mode each one opens. Any other launch tab is left in
 * [DemoSettings.initialTab] for mode 0's own demo when [defaultModeReadsTab] is set — so
 * `ar-placement?tab=1` still opens the wall inside the Place mode — and is dropped otherwise,
 * so it cannot pre-select a tab of the next demo opened.
 *
 * **Where the switch is drawn.** By default, as the mode pill over the scene. A card whose
 * second mode is an option of the first rather than an experience of its own — Room Scan's
 * "Session MP4" — sets [switchInSheet]: the scene keeps the pill's row, and the modes are the
 * first row of the settings sheet (#4379).
 */
@Composable
fun DemoModeHost(
    modes: List<DemoMode>,
    tabToMode: Map<Int, Int>,
    defaultModeReadsTab: Boolean = false,
    switchInSheet: Boolean = false,
    content: @Composable (mode: Int) -> Unit,
) {
    val sampleId = LocalSampleId.current
    var mode by rememberSaveable {
        mutableIntStateOf(initialHostMode(tabToMode, defaultModeReadsTab, modes.size))
    }
    val switch = DemoModeSwitch(
        modes = modes,
        selected = mode,
        onSelect = { selected ->
            if (selected != mode) {
                mode = selected
                logSampleModeChange(sampleId, modes[selected].key)
            }
        },
        inSheet = switchInSheet,
    )
    CompositionLocalProvider(LocalDemoModeSwitch provides switch) {
        key(mode) { content(mode) }
    }
}

/**
 * The mode a [DemoModeHost] opens on, consuming the launch tab when the host owns it. Pure
 * apart from [DemoSettings.initialTab], so the routing is unit-tested on the JVM.
 */
internal fun initialHostMode(
    tabToMode: Map<Int, Int>,
    defaultModeReadsTab: Boolean,
    modeCount: Int,
): Int {
    val tab = DemoSettings.initialTab ?: return 0
    val owned = tabToMode[tab]
    if (owned == null && defaultModeReadsTab) return 0
    DemoSettings.initialTab = null
    return owned?.takeIf { it in 0 until modeCount } ?: 0
}

/**
 * The shared mode pill (`mode-pill-*` tokens, `DESIGN.md`): the same segmented glass pill
 * Cosmos draws for its Starlight / Spacetime views, so a consolidated card reads as one demo
 * with modes rather than a stack of screens.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DemoModePill(switch: DemoModeSwitch, modifier: Modifier = Modifier) {
    val pill = SceneViewTokens.ModePill
    Surface(
        shape = CircleShape,
        color = pill.container,
        contentColor = pill.onContainer,
        border = BorderStroke(pill.outlineWidth, pill.outline),
        modifier = modifier.testTag(DEMO_MODE_PILL_TAG),
    ) {
        ConnectedChoiceRow(
            options = switch.modes.indices.toList(),
            selected = switch.selected,
            onSelect = switch.onSelect,
            label = { stringResource(switch.modes[it].labelRes) },
            modifier = Modifier.padding(horizontal = SceneViewTokens.Space.xs),
            optionTestTag = { "demo_mode_${switch.modes[it].key}" },
            colors = ToggleButtonDefaults.colors(
                containerColor = pill.container,
                contentColor = pill.onContainer,
                checkedContainerColor = pill.selectedContainer,
                checkedContentColor = pill.onSelected,
            ),
            fillWidth = false,
        )
    }
}

/**
 * The same switch as a row of the settings sheet (#4379), for a host that set `switchInSheet`:
 * the sheet's own segmented row, full width, in the theme's colours. The options keep the
 * pill's test tags (`demo_mode_<key>`), so a flow that picks a mode names it the same way.
 */
@Composable
internal fun DemoModeRow(switch: DemoModeSwitch, modifier: Modifier = Modifier) {
    ConnectedChoiceRow(
        options = switch.modes.indices.toList(),
        selected = switch.selected,
        onSelect = switch.onSelect,
        label = { stringResource(switch.modes[it].labelRes) },
        modifier = modifier.testTag(DEMO_MODE_ROW_TAG),
        optionTestTag = { "demo_mode_${switch.modes[it].key}" },
    )
}

/** Test tag of the shared [DemoModePill]. */
const val DEMO_MODE_PILL_TAG = "demo-mode-pill"

/** Test tag of [DemoModeRow], the mode switch inside the settings sheet. */
const val DEMO_MODE_ROW_TAG = "demo-mode-row"
