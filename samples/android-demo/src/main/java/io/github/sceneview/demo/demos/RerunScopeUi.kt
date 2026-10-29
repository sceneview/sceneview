package io.github.sceneview.demo.demos

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Chair
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Yard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.demos.internal.HomeRoom
import io.github.sceneview.demo.demos.internal.HomeScan
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.demos.internal.ScanScope
import io.github.sceneview.demo.demos.internal.ScanScopeCopy
import io.github.sceneview.demo.demos.internal.SurveyReport
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Radius
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.theme.SceneViewTokens.Type
import io.github.sceneview.demo.theme.StageChrome

/*
 * 3D Scan asks first what is scanned (#4647): an object, a room, or a whole home room by room.
 * Laid out as Polycam's and Reality Composer's capture pickers: one card per kind, its icon, what
 * it is for and how the scan goes, the choice ticked, and one primary action that names it. The
 * last choice is remembered and comes back ticked. A themed stage (#4080), like the landing.
 *
 * The whole home ends on its summary: each room with its own plan and figures, and the Rooms
 * block of #4543 with every room and their total.
 */

private val stage: StageChrome @Composable get() = LocalStageChrome.current

/** The last [ScanScope] chosen, kept across launches. SharedPreferences, as [io.github.sceneview.demo.DemoSheetDetentStore]. */
internal object ScanScopeStore {
    private const val PREFS = "sceneview_demo_ar_rerun"
    private const val KEY = "ar_rerun_scan_scope"

    fun last(context: Context): ScanScope =
        ScanScope.fromKey(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun remember(context: Context, scope: ScanScope) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, scope.key).apply()
    }
}

/**
 * The page of the scope choice: a heading, the four kinds, and the action that starts that scan.
 * The action is pinned at the bottom, above the navigation bar, and the kinds scroll behind it
 * (#4730): it never moves and is never covered, and the last kind scrolls clear above it.
 */
@Composable
internal fun ScanScopePage(selected: ScanScope, onSelect: (ScanScope) -> Unit, onStart: () -> Unit) {
    StagePage(bottomBar = { StartCard(ScanScopeCopy.start(selected), onClick = onStart) }) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(
                text = ScanScopeCopy.CHOOSER_TITLE,
                style = Type.display.copy(color = stage.onGlass),
                modifier = Modifier.semantics { heading() },
            )
            Text(text = ScanScopeCopy.CHOOSER_BODY, style = Type.body.copy(color = stage.onGlassMuted))
        }
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            ScanScope.entries.forEach { scope ->
                ScopeCard(scope, selected = scope == selected, onClick = { onSelect(scope) })
            }
        }
    }
}

/** One kind of scan, as a radio card: the icon in its well, what it is for, how it goes. */
@Composable
private fun ScopeCard(scope: ScanScope, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(stage.glass)
            .border(
                width = if (selected) SELECTED_BORDER else UNSELECTED_BORDER,
                color = if (selected) stage.accent else stage.onGlass.copy(alpha = BORDER_ALPHA),
                shape = shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(Space.md)
            .testTag("$SCAN_SCOPE_TAG-${scope.key}"),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(SceneViewTokens.Layout.touchTarget)
                .background(
                    if (selected) stage.accent else stage.onGlass.copy(alpha = WELL_ALPHA),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(scope.icon, contentDescription = null, tint = if (selected) stage.onAccent else stage.onGlass)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(ScanScopeCopy.title(scope), style = Type.card.copy(color = stage.onGlass))
            Text(ScanScopeCopy.detail(scope), style = Type.body.copy(color = stage.onGlass))
            Text(ScanScopeCopy.how(scope), style = Type.caption.copy(color = stage.onGlassMuted))
        }
        Icon(
            imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) stage.accent else stage.onGlassMuted,
        )
    }
}

private val ScanScope.icon: ImageVector
    get() = when (this) {
        ScanScope.Object -> Icons.Rounded.Chair
        ScanScope.Room -> Icons.Rounded.MeetingRoom
        ScanScope.Home -> Icons.Rounded.Home
        ScanScope.Area -> Icons.Rounded.Yard
    }

/** The primary action on the accent fill, with the privacy promise under it, as the landing's. */
@Composable
private fun StartCard(label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SceneViewTokens.Layout.touchTarget + Space.md)
            .clip(RoundedCornerShape(Radius.lg))
            .background(stage.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.sm)
            .testTag(SCAN_SCOPE_START_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = Type.title.copy(color = stage.onAccent))
        Text(ScanCopy.PRIVACY, style = Type.caption.copy(color = stage.onAccent.copy(alpha = MUTED_ALPHA)))
    }
}

/**
 * A whole home's summary: the Rooms block with each room and the total, then each room with its
 * own plan and figures. A room still being measured says so; the total waits for it.
 */
@Composable
internal fun HomeSummaryPage(home: HomeScan, onOpenPlan: (HomeRoom) -> Unit, onScanAnother: () -> Unit) {
    val words = rememberPlanWords()
    StagePage {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(
                text = ScanScopeCopy.HOME_TITLE,
                style = Type.display.copy(color = stage.onGlass),
                modifier = Modifier.semantics { heading() },
            )
            Text(ScanScopeCopy.roomCount(home.rooms.size), style = Type.body.copy(color = stage.onGlassMuted))
        }
        if (home.rooms.isNotEmpty()) {
            PlanSection(words.survey.rooms, testTag = HOME_ROOMS_TAG) {
                val areas = home.areas { words.survey.room.format(it) }
                SurveyReport.roomRows(areas, words.survey).forEach { FigureRow(it) }
            }
        }
        home.rooms.forEach { room -> HomeRoomSection(room, words, onOpenPlan = { onOpenPlan(room) }) }
        SummaryAction(Icons.Rounded.Add, ScanScopeCopy.SCAN_ANOTHER_ROOM, onClick = onScanAnother)
        Text(words.survey.notice, style = Type.caption.copy(color = stage.onGlassMuted))
    }
}

/** One room of the summary: its name and title, its plan, its figures, and Open plan. */
@Composable
private fun HomeRoomSection(room: HomeRoom, words: PlanWords, onOpenPlan: () -> Unit) {
    val survey = room.survey
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            text = words.survey.room.format(room.number),
            style = Type.title.copy(color = stage.onGlass),
            modifier = Modifier.padding(top = Space.sm).semantics { heading() },
        )
        if (survey == null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Space.md),
                    color = stage.accent,
                    strokeWidth = Space.xs / 2,
                )
                Text(ScanScopeCopy.MEASURING, style = Type.body.copy(color = stage.onGlassMuted))
            }
            return@Column
        }
        if (survey.hasPlan) PlanCard(survey, words)
        PlanSection(null, caption = room.title) {
            SurveyReport.rows(survey, words.survey).forEach { FigureRow(it) }
            SurveyReport.hints(survey, words.survey).forEach {
                Text(it, style = Type.body.copy(color = stage.onGlass))
            }
        }
        SummaryAction(null, ScanScopeCopy.OPEN_PLAN, onClick = onOpenPlan)
    }
}

/** A secondary action on glass. */
@Composable
private fun SummaryAction(icon: ImageVector?, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .clip(RoundedCornerShape(Radius.md))
            .background(stage.glass)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = stage.onGlass)
        Text(label, style = Type.body.copy(color = stage.onGlass))
    }
}

/**
 * The landing's scrolling page: the stage ground, clear of the header. Without a [bottomBar] the
 * last row scrolls clear of the settings button, as the landing's. With one, the bar is pinned at
 * the bottom over a fade into the ground, above the navigation bar, and the page scrolls behind
 * it, ending a gutter above the bar's top.
 */
@Composable
private fun StagePage(bottomBar: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    var barHeight by remember { mutableStateOf(0.dp) }
    Box(
        modifier = Modifier.fillMaxSize().background(stage.ground),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (bottomBar == null) {
                        Modifier.rerunPageScroll()
                    } else {
                        Modifier
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                            )
                            .padding(top = RerunHeaderClearance)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = barHeight)
                    },
                )
                .padding(horizontal = Space.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = RerunPageMaxWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) { content() }
        }
        if (bottomBar != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { barHeight = with(density) { it.height.toDp() } }
                    .background(
                        Brush.verticalGradient(
                            0f to stage.ground.copy(alpha = 0f),
                            BAR_FADE_STOP to stage.ground,
                        ),
                    )
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    )
                    .padding(start = Space.md, top = Space.lg, end = Space.md, bottom = Space.md),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.widthIn(max = RerunPageMaxWidth).fillMaxWidth()) { bottomBar() }
            }
        }
    }
}

internal const val SCAN_SCOPE_TAG = "rerun-scan-scope"
internal const val SCAN_SCOPE_START_TAG = "rerun-scan-scope-start"
internal const val HOME_ROOMS_TAG = "rerun-home-rooms"

private val SELECTED_BORDER = 2.dp
private val UNSELECTED_BORDER = 1.dp
private const val BORDER_ALPHA = 0.12f
private const val WELL_ALPHA = 0.08f
private const val MUTED_ALPHA = 0.72f

/** Where the pinned bar's fade reaches the full ground: within its top gutter, above the action. */
private const val BAR_FADE_STOP = 0.2f
