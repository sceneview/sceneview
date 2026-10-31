package io.github.sceneview.demo.demos

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.SquareFoot
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.content.FileProvider
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LocalDemoBottomChromeCover
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SETTINGS_FAB_RESERVED_SPACE
import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.ArDebugGeometry
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.MeasureUnitSystem
import io.github.sceneview.demo.demos.internal.PlanLine
import io.github.sceneview.demo.demos.internal.PlanMetrics
import io.github.sceneview.demo.demos.internal.PlanPage
import io.github.sceneview.demo.demos.internal.PlanStroke
import io.github.sceneview.demo.demos.internal.PlanTextRole
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.RescanZone
import io.github.sceneview.demo.demos.internal.ResultFloor
import io.github.sceneview.demo.demos.internal.orPlanes
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.SyntheticScene
import io.github.sceneview.demo.demos.internal.RoomPlanDrawing
import io.github.sceneview.demo.demos.internal.RoomPlanLayout
import io.github.sceneview.demo.demos.internal.RoomSurvey
import io.github.sceneview.demo.demos.internal.RoomSurveyInput
import io.github.sceneview.demo.demos.internal.RoomSurveyor
import io.github.sceneview.demo.demos.internal.ScanDevice
import io.github.sceneview.demo.demos.internal.SurveyFormat
import io.github.sceneview.demo.demos.internal.SurveyGap
import io.github.sceneview.demo.demos.internal.SurveyReport
import io.github.sceneview.demo.demos.internal.SurveyRow
import io.github.sceneview.demo.demos.internal.SurveyWording
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Radius
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.theme.SceneViewTokens.Type
import io.github.sceneview.demo.theme.StageChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The Plan view of a scan (#4540): the room's survey — floor area, length × width, perimeter,
 * ceiling height, each wall — and its dimensioned floor plan, shared as a one-page PDF or as a
 * few lines of text. The figures come from internal/RoomSurvey.kt; this file draws them, on the
 * screen with the stage's ink and on paper with the light stage's, through the same marks.
 *
 * Nothing leaves the phone except through the share sheet.
 */

private val stage: StageChrome @Composable get() = LocalStageChrome.current

/** Every word the Plan view and its exports use, read once from the resources. */
internal class PlanWords(
    val survey: SurveyWording,
    val surveyTitle: String,
    val emptyTitle: String,
    val legendSeen: String,
    val legendUnseen: String,
    /** Shown on the plan's card where the screen is too short to show it whole. */
    val scrollHint: String,
    val pdfTitle: String,
    /** `%1$d` the scale's denominator. */
    val pdfScale: String,
    /** `%1$d` the page, `%2$d` how many there are. */
    val pdfPage: String,
    val pdfWallsContinued: String,
    /** `%1$s` the scan's title. */
    val pdfFile: String,
)

/**
 * The Plan view's words under the phone's language, its figures in [units]: by default that
 * language's, feet and inches in US English, metres everywhere else ([SurveyFormat.units]). Their
 * decimal mark is [locale]'s, the phone's by default: `4,00 m` in French, `4.00 m` in English.
 */
@Composable
internal fun rememberPlanWords(
    units: MeasureUnitSystem = SurveyFormat.units(LocalLocale.current.platformLocale),
    locale: Locale = LocalLocale.current.platformLocale,
): PlanWords = planWords(units, locale)

@Composable
private fun planWords(units: MeasureUnitSystem, locale: Locale): PlanWords = PlanWords(
    survey = SurveyWording(
        heading = stringResource(R.string.demo_ar_rerun_plan_heading),
        area = stringResource(R.string.demo_ar_rerun_plan_area),
        dimensions = stringResource(R.string.demo_ar_rerun_plan_dimensions),
        perimeter = stringResource(R.string.demo_ar_rerun_plan_perimeter),
        height = stringResource(R.string.demo_ar_rerun_plan_height),
        volume = stringResource(R.string.demo_ar_rerun_plan_volume),
        rooms = stringResource(R.string.demo_ar_rerun_plan_rooms),
        room = stringResource(R.string.demo_ar_rerun_plan_room),
        total = stringResource(R.string.demo_ar_rerun_plan_total),
        span = stringResource(R.string.demo_ar_rerun_plan_span),
        walls = stringResource(R.string.demo_ar_rerun_plan_walls),
        wall = stringResource(R.string.demo_ar_rerun_plan_wall),
        notSeen = stringResource(R.string.demo_ar_rerun_plan_not_seen),
        notMeasured = stringResource(R.string.demo_ar_rerun_plan_not_measured),
        estimated = stringResource(R.string.demo_ar_rerun_plan_estimated),
        estimateNote = stringResource(R.string.demo_ar_rerun_plan_estimate_note),
        spreadNote = stringResource(R.string.demo_ar_rerun_plan_spread_note),
        notice = stringResource(R.string.demo_ar_rerun_plan_notice),
        madeWith = stringResource(R.string.demo_ar_rerun_plan_made_with),
        scaleBar = stringResource(
            when (units) {
                MeasureUnitSystem.Metric -> R.string.demo_ar_rerun_plan_scale_bar
                MeasureUnitSystem.Imperial -> R.string.demo_ar_rerun_plan_scale_bar_feet
            },
        ),
        gaps = mapOf(
            SurveyGap.NoDepth to stringResource(R.string.demo_ar_rerun_plan_gap_no_depth),
            SurveyGap.NoWalls to stringResource(R.string.demo_ar_rerun_plan_gap_no_walls),
            SurveyGap.WallEnd to stringResource(R.string.demo_ar_rerun_plan_gap_wall_end),
            SurveyGap.MissingWall to stringResource(R.string.demo_ar_rerun_plan_gap_missing_wall),
            SurveyGap.ObliqueWall to stringResource(R.string.demo_ar_rerun_plan_gap_oblique_wall),
            SurveyGap.Ceiling to stringResource(R.string.demo_ar_rerun_plan_gap_ceiling),
            SurveyGap.Unmeasured to stringResource(R.string.demo_ar_rerun_plan_gap_unmeasured),
        ),
        units = units,
        locale = locale,
    ),
    surveyTitle = stringResource(R.string.demo_ar_rerun_plan_survey_title),
    emptyTitle = stringResource(R.string.demo_ar_rerun_plan_empty_title),
    legendSeen = stringResource(R.string.demo_ar_rerun_plan_legend_seen),
    legendUnseen = stringResource(R.string.demo_ar_rerun_plan_legend_unseen),
    scrollHint = stringResource(R.string.demo_ar_rerun_plan_scroll_hint),
    pdfTitle = stringResource(R.string.demo_ar_rerun_plan_pdf_title),
    pdfScale = stringResource(
        when (units) {
            MeasureUnitSystem.Metric -> R.string.demo_ar_rerun_plan_pdf_scale
            MeasureUnitSystem.Imperial -> R.string.demo_ar_rerun_plan_pdf_scale_feet
        },
    ),
    pdfPage = stringResource(R.string.demo_ar_rerun_plan_pdf_page),
    pdfWallsContinued = stringResource(R.string.demo_ar_rerun_plan_pdf_walls_continued),
    pdfFile = stringResource(R.string.demo_ar_rerun_plan_pdf_file),
)

/** The plan's heading: the scan's [title] and, once, its [date] — `null` when the title says it. */
internal data class PlanHeading(val title: String, val date: String?)

/**
 * How the plan names scan [title], made at [epochSeconds] (#4714): the date written once, in the
 * format of every other screen ([scanDate]) with its year, as a document wants. A title that
 * already says when — "Room · Oct 19, 11:58 AM" — gets the year in place, "Room · Oct 19, 2026,
 * 11:58 AM", rather than the date again after it. A scan that does not say when it was made has
 * no date: the day the plan is read is not the day the room was scanned.
 */
internal fun planHeading(title: String, epochSeconds: Long?, locale: Locale = Locale.US): PlanHeading {
    val millis = (epochSeconds ?: return PlanHeading(title, null)) * MILLIS_PER_SECOND
    val full = scanDateWithYear(millis, locale)
    val short = scanDate(millis, locale)
    return if (title.contains(short)) PlanHeading(title.replace(short, full), null) else PlanHeading(title, full)
}

/** [media]'s survey, measured on the calling thread: call it off the main one. */
internal fun surveyOf(media: RerunReplayMedia): RoomSurvey {
    val cloud = media.dense?.cloud ?: return RoomSurvey.empty(SurveyGap.NoDepth)
    val last = media.trace.let { it.frameAt(it.duration) }
    // The cloud holds no wall when the depth stalled while ARCore kept finding them: the walls
    // the capture counted then give the plan, not "scan the walls" under a count of ten.
    return RoomSurveyor.survey(RoomSurveyInput(cloud.positions, cloud.normals, last.trail))
        .orPlanes(last.planes, ArDebugGeometry.floorHeight(last))
}

/**
 * A scan's floor plan as a screen of its own, opened from the export sheet: the scan's [media]
 * measured off the main thread, then its [RerunPlanView] under the demo's header. Back returns to
 * the screen it was opened from ([onBack]). The settings sheet over this page of figures is
 * opaque: rows reading through its glass would mix with its own.
 */
@Composable
internal fun RerunPlanScreen(media: RerunReplayMedia?, title: String, date: String?, onBack: () -> Unit) {
    val survey = rememberRoomSurvey(media, wanted = true).survey
    // The parts still to scan, laid on the plan (#4735): read off the same cloud as its walls, off
    // the main thread, and only for a plan the scan's frame can be laid on.
    val zones by produceState(emptyList<RescanZone>(), media, survey) {
        value = emptyList()
        if (survey == null || PlanPage.of(survey) == null) return@produceState
        value = withContext(Dispatchers.Default) { zonesOf(media?.dense?.cloud, survey) }
    }
    DemoScaffold(
        title = stringResource(R.string.demo_ar_rerun_plan_export_title),
        onBack = onBack,
        controls = { Text(stringResource(R.string.demo_ar_rerun_plan_about), style = Type.body) },
        themedStage = true,
        opaqueSheet = true,
        hasScene = false,
    ) {
        RerunPlanView(survey, title, date, zones = zones)
    }
}

/**
 * The Plan view: the dimensioned plan, the survey's table, what the scan lacks, and the two
 * shares. A phone on its side puts the table beside the plan. Its figures, on screen, on the PDF
 * and in the text alike, are in [units], with [locale]'s decimal mark ([rememberPlanWords]).
 *
 * The page is text, and the header's pills float over the stage without a ground of their own:
 * the page starts under them and is clipped there, so nothing scrolls behind a pill.
 *
 * @param zones the parts of the scan still to scan, in the scan's frame, laid on the plan (#4735).
 */
@Composable
internal fun RerunPlanView(
    survey: RoomSurvey?,
    title: String,
    date: String?,
    modifier: Modifier = Modifier,
    units: MeasureUnitSystem = SurveyFormat.units(LocalLocale.current.platformLocale),
    locale: Locale = LocalLocale.current.platformLocale,
    zones: List<RescanZone> = emptyList(),
) {
    val words = rememberPlanWords(units, locale)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val writing = remember { AtomicBoolean() }
    val failed = stringResource(R.string.demo_ar_rerun_plan_share_failed)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(stage.ground)
            .testTag(RERUN_PLAN_TAG),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (survey == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Space.md, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = stage.accent)
                Text(
                    text = stringResource(R.string.demo_ar_rerun_plan_measuring),
                    style = Type.body.copy(color = stage.onGlassMuted),
                )
            }
            return@BoxWithConstraints
        }
        val wide = maxWidth > maxHeight
        // A file share writes its file in the background, and says so when it could not, in a
        // toast made on the main thread whichever thread the write resumes on. A tap while one is
        // being written is let go: two quick taps write one file, never half of two.
        fun shareFile(share: suspend () -> Boolean) {
            if (!writing.compareAndSet(false, true)) return
            scope.launch {
                try {
                    if (!share()) {
                        withContext(Dispatchers.Main) { Toast.makeText(context, failed, Toast.LENGTH_SHORT).show() }
                    }
                } finally {
                    writing.set(false)
                }
            }
        }
        val sharePdf = { shareFile { sharePlanPdf(context, survey, words, title, date) } }
        val shareDxf = { shareFile { sharePlanDxf(context, survey, words, title) } }
        val shareText = { sharePlanText(context, SurveyReport.text(survey, words.survey, title, date), title) }
        val dock = maxOf(SETTINGS_FAB_RESERVED_SPACE, LocalDemoBottomChromeCover.current) + Space.md
        // Under the header: a scrolling column clips to its own box, which starts below the pills.
        val frame = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = Space.md)
            .padding(top = RerunHeaderClearance)
        if (wide && survey.hasPlan) {
            // On its side the survey scrolls beside the plan. Where the header and the dock leave
            // the height the flattest plan asks for (a tablet), the outline is fitted whole to its
            // card. A phone on its side leaves a strip: there the plan keeps its size, scrolls on
            // its own and says so, rather than being squeezed into a drawing nobody can read.
            Row(modifier = frame, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                BoxWithConstraints(Modifier.weight(PLAN_SHARE).fillMaxHeight()) {
                    if (maxHeight - dock >= maxWidth / MAX_PLAN_RATIO) {
                        PlanCard(survey, words, Modifier.fillMaxHeight().padding(bottom = dock), fill = true, zones = zones)
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = dock)) {
                            PlanCard(survey, words, hint = words.scrollHint, zones = zones)
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = dock),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    SurveyDetails(survey, words, title, date, PlanShares(sharePdf, shareDxf, shareText))
                }
            }
        } else {
            val page = frame.verticalScroll(rememberScrollState()).padding(bottom = dock)
            Column(modifier = page, horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    modifier = Modifier.widthIn(max = RerunPageMaxWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    if (survey.hasPlan) PlanCard(survey, words, zones = zones)
                    SurveyDetails(survey, words, title, date, PlanShares(sharePdf, shareDxf, shareText))
                }
            }
        }
    }
}

/**
 * The plan on its card: the outline to scale on the grid every result view stands on, its
 * dimensions, the parts still to scan, and what a dashed wall and a dashed amber box mean.
 *
 * @param fill the drawing takes the height the card is given instead of the plan's own shape.
 * @param hint a line above the drawing, for a card that does not show whole.
 * @param zones the parts still to scan, in the scan's frame: none on a plan it cannot be laid on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanCard(
    survey: RoomSurvey,
    words: PlanWords,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    hint: String? = null,
    zones: List<RescanZone> = emptyList(),
) {
    val density = LocalDensity.current
    val unit = density.density * density.fontScale.coerceAtMost(MAX_PLAN_FONT_SCALE)
    val ink = PlanInk(
        wall = stage.onGlass.toArgb(),
        muted = stage.onGlassMuted.toArgb(),
        text = stage.onGlass.toArgb(),
        fill = stage.onGlass.copy(alpha = FLOOR_ALPHA).toArgb(),
    )
    val floor = PlanFloorInk(
        gridMinor = stage.debug.gridMinor.toArgb(),
        gridMajor = stage.debug.gridMajor.toArgb(),
        zone = ZoneInk.frame(stage).toArgb(),
        veil = ZoneInk.veil.toArgb(),
    )
    val texts = remember(survey, words) { SurveyReport.planTexts(survey, words.survey) }
    val grid = remember(survey) { ResultFloor.planGrid(survey) }
    val page = remember(survey) { PlanPage.of(survey) }
    val quads = remember(page, zones) { page?.let { p -> zones.map { p.quad(it.box) } }.orEmpty() }
    val description = stringResource(R.string.demo_ar_rerun_plan_content_description)
    val ratio = (survey.planLength / survey.planWidth.coerceAtLeast(MIN_PLAN_SIDE_M))
        .coerceIn(MIN_PLAN_RATIO, MAX_PLAN_RATIO)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(stage.glass)
            .padding(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (hint != null) {
            Text(
                text = hint,
                style = Type.caption.copy(color = stage.onGlassMuted),
                modifier = Modifier.padding(horizontal = Space.sm).padding(top = Space.xs),
            )
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .then(if (fill) Modifier.weight(1f) else Modifier.aspectRatio(ratio))
                .semantics { contentDescription = description }
                .testTag(RERUN_PLAN_DRAWING_TAG)
                .drawWithCache {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    val metrics = PlanMetrics(unit) { text, textSize ->
                        paint.textSize = textSize
                        paint.measureText(text)
                    }
                    val drawing = RoomPlanLayout.layout(survey, texts, size.width, size.height, metrics)
                    val origin = ResultFloor.planOrigin(drawing, survey)
                    val under = origin?.let { o -> { canvas: Canvas -> drawPlanFloor(canvas, drawing, o, grid, quads, floor, unit) } }
                    onDrawBehind { drawIntoCanvas { drawRoomPlan(it.nativeCanvas, drawing, ink, unit, under) } }
                },
        )
        val unseen = survey.walls.any { !it.seen || it.unseen.isNotEmpty() }
        if (unseen || quads.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(horizontal = Space.sm).padding(bottom = Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                if (unseen) {
                    LegendMark(words.legendSeen, dashed = false, ink = ink, unit = unit)
                    LegendMark(words.legendUnseen, dashed = true, ink = ink, unit = unit)
                }
                if (quads.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                        ZoneSwatch()
                        Text(stringResource(R.string.scan_result_not_scanned), style = Type.caption.copy(color = stage.onGlassMuted))
                    }
                }
            }
        }
        // The plan never shows without it: it is what a capture of this card carries.
        Text(
            text = words.survey.notice,
            style = Type.caption.copy(color = stage.onGlassMuted),
            modifier = Modifier.padding(horizontal = Space.sm).padding(bottom = Space.xs),
        )
    }
}

@Composable
private fun LegendMark(label: String, dashed: Boolean, ink: PlanInk, unit: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(width = Space.lg, height = Space.md)
                .drawWithCache {
                    val stroke = if (dashed) PlanStroke.WallUnseen else PlanStroke.Wall
                    val line = PlanLine(0f, size.height / 2, size.width, size.height / 2, stroke)
                    val drawing = RoomPlanDrawing(size.width, size.height, 0f, FloatArray(0), listOf(line), emptyList())
                    onDrawBehind { drawIntoCanvas { drawRoomPlan(it.nativeCanvas, drawing, ink, unit) } }
                },
        )
        Text(label, style = Type.caption.copy(color = stage.onGlassMuted))
    }
}

/** The survey under (or beside) the plan: its figures, its walls, what is missing, the notes and the shares. */
@Composable
private fun SurveyDetails(survey: RoomSurvey, words: PlanWords, title: String, date: String?, shares: PlanShares) {
    val hints = SurveyReport.hints(survey, words.survey)
    PlanSection(words.surveyTitle, caption = SurveyReport.heading(words.survey, title, date)) {
        SurveyReport.rows(survey, words.survey).forEach { FigureRow(it) }
    }
    if (survey.hasPlan) {
        PlanSection(words.survey.rooms, testTag = RERUN_PLAN_ROOMS_TAG) {
            SurveyReport.roomRows(SurveyReport.areas(survey, words.survey), words.survey).forEach { FigureRow(it) }
        }
    }
    if (hints.isNotEmpty()) {
        PlanSection(words.emptyTitle.takeIf { !survey.hasPlan }, testTag = RERUN_PLAN_HINTS_TAG) {
            hints.forEach { Text(text = it, style = Type.body.copy(color = stage.onGlass)) }
        }
    }
    if (survey.walls.isNotEmpty()) {
        PlanSection(words.survey.walls) {
            SurveyReport.wallRows(survey, words.survey).forEach { FigureRow(it) }
        }
    }
    // The share menu: the PDF, the same plan as a DXF drawing for a CAD tool, then the text.
    // One share a line, so no label is cut short on a phone.
    Column(
        modifier = Modifier.fillMaxWidth().testTag(RERUN_PLAN_SHARES_TAG),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        ShareButton(
            Icons.Rounded.PictureAsPdf, stringResource(R.string.demo_ar_rerun_plan_share_pdf), accent = true,
            modifier = Modifier.fillMaxWidth().testTag(RERUN_PLAN_SHARE_PDF_TAG), onClick = shares.pdf,
        )
        if (survey.hasPlan) {
            ShareButton(
                Icons.Rounded.SquareFoot, stringResource(R.string.demo_ar_rerun_plan_share_dxf), accent = false,
                modifier = Modifier.fillMaxWidth().testTag(RERUN_PLAN_SHARE_DXF_TAG), onClick = shares.dxf,
            )
        }
        ShareButton(
            Icons.Rounded.TextSnippet, stringResource(R.string.demo_ar_rerun_plan_share_text), accent = false,
            modifier = Modifier.fillMaxWidth().testTag(RERUN_PLAN_SHARE_TEXT_TAG), onClick = shares.text,
        )
    }
    Column(Modifier.padding(horizontal = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (!survey.hasPlan) Text(words.survey.notice, style = Type.caption.copy(color = stage.onGlass))
        // The same notes as the PDF and the shared text: what "≈" means, what "±" is.
        SurveyReport.notes(survey, words.survey).forEach {
            Text(it, style = Type.caption.copy(color = stage.onGlassMuted))
        }
    }
}

@Composable
internal fun PlanSection(
    title: String?,
    caption: String? = null,
    testTag: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(stage.glass)
            .padding(Space.md)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (title != null) {
            Text(title, style = Type.card.copy(color = stage.onGlass), modifier = Modifier.semantics { heading() })
        }
        if (caption != null) Text(caption, style = Type.caption.copy(color = stage.onGlassMuted))
        content()
    }
}

@Composable
internal fun FigureRow(row: SurveyRow) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.label, style = Type.body.copy(color = stage.onGlassMuted), modifier = Modifier.weight(1f))
        Text(
            text = row.value,
            style = Type.body.copy(
                color = if (row.muted) stage.onGlassMuted else stage.onGlass,
                fontFeatureSettings = "tnum",
            ),
        )
    }
}

/** The plan's three shares, each on its own action: the PDF, the DXF drawing and the text. */
private class PlanShares(val pdf: () -> Unit, val dxf: () -> Unit, val text: () -> Unit)

@Composable
private fun ShareButton(
    icon: ImageVector,
    label: String,
    accent: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ground = if (accent) stage.accent else stage.glass
    val ink = if (accent) stage.onAccent else stage.onGlass
    Row(
        modifier = modifier
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .clip(RoundedCornerShape(Radius.md))
            .background(ground)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink)
        Text(label, style = Type.body.copy(color = ink), maxLines = 1)
    }
}

// ── The plan's marks, on a screen or on paper ────────────────────────────────────────────────

/** The colours a plan is painted with, ARGB. */
internal class PlanInk(val wall: Int, val muted: Int, val text: Int, val fill: Int)

/** What the screen's plan lays on its floor (#4735): its grid's two inks, a part's frame and veil. */
private class PlanFloorInk(val gridMinor: Int, val gridMajor: Int, val zone: Int, val veil: Int)

/**
 * The plan's floor (#4735): the grid every result view stands on, a line every quarter metre and
 * a bolder one every metre, from the room's corner and clipped to its floor, then each part still
 * to scan — [quads], page metres — as a dashed amber frame over its veil. [origin] is where the
 * page's origin falls in [drawing].
 */
private fun drawPlanFloor(
    canvas: Canvas,
    drawing: RoomPlanDrawing,
    origin: FloatArray,
    grid: ResultFloor.PlanGrid,
    quads: List<FloatArray>,
    ink: PlanFloorInk,
    unit: Float,
) {
    if (drawing.floor.size < MIN_OUTLINE_FLOATS) return
    val scale = drawing.pxPerMetre
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val floor = Path()
    floor.moveTo(drawing.floor[0], drawing.floor[1])
    for (i in 1 until drawing.floor.size / 2) floor.lineTo(drawing.floor[i * 2], drawing.floor[i * 2 + 1])
    floor.close()
    canvas.save()
    canvas.clipPath(floor)
    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.BUTT
    for ((lines, color, width) in listOf(
        Triple(grid.minor, ink.gridMinor, GRID_MINOR_STROKE),
        Triple(grid.major, ink.gridMajor, GRID_MAJOR_STROKE),
    )) {
        paint.color = color
        paint.strokeWidth = width * unit
        for (k in 0 until lines.size / 4) {
            canvas.drawLine(
                origin[0] + lines[k * 4] * scale, origin[1] + lines[k * 4 + 1] * scale,
                origin[0] + lines[k * 4 + 2] * scale, origin[1] + lines[k * 4 + 3] * scale,
                paint,
            )
        }
    }
    canvas.restore()
    val box = Path()
    for (quad in quads) {
        box.rewind()
        box.moveTo(origin[0] + quad[0] * scale, origin[1] + quad[1] * scale)
        for (i in 1 until quad.size / 2) box.lineTo(origin[0] + quad[i * 2] * scale, origin[1] + quad[i * 2 + 1] * scale)
        box.close()
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.color = ink.veil
        canvas.drawPath(box, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ZONE_STROKE * unit
        paint.color = ink.zone
        paint.pathEffect = DashPathEffect(floatArrayOf(DASH_ON * unit, DASH_OFF * unit), 0f)
        canvas.drawPath(box, paint)
    }
}

/** Paints [drawing] on [canvas]; [unit] is one dp (one point on paper), the measure of every stroke. */
internal fun drawRoomPlan(
    canvas: Canvas,
    drawing: RoomPlanDrawing,
    ink: PlanInk,
    unit: Float,
    under: ((Canvas) -> Unit)? = null,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val path = Path()
    if (drawing.floor.size >= MIN_OUTLINE_FLOATS) {
        path.moveTo(drawing.floor[0], drawing.floor[1])
        for (i in 1 until drawing.floor.size / 2) path.lineTo(drawing.floor[i * 2], drawing.floor[i * 2 + 1])
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = ink.fill
        canvas.drawPath(path, paint)
    }
    // What lies on the floor, under the walls and their dimensions: the screen's grid and parts
    // still to scan (#4735). The PDF draws none.
    under?.invoke(canvas)
    paint.style = Paint.Style.STROKE
    val dash = DashPathEffect(floatArrayOf(DASH_ON * unit, DASH_OFF * unit), 0f)
    for (line in drawing.lines) {
        paint.pathEffect = if (line.stroke == PlanStroke.WallUnseen) dash else null
        paint.strokeCap = if (line.stroke == PlanStroke.Wall) Paint.Cap.SQUARE else Paint.Cap.BUTT
        paint.strokeWidth = unit * when (line.stroke) {
            PlanStroke.Wall -> WALL_STROKE
            PlanStroke.WallUnseen -> UNSEEN_STROKE
            PlanStroke.Tick, PlanStroke.Scale -> TICK_STROKE
            PlanStroke.Dimension, PlanStroke.Extension -> HAIR_STROKE
        }
        paint.color = when (line.stroke) {
            PlanStroke.Wall, PlanStroke.Tick, PlanStroke.Scale -> ink.wall
            else -> ink.muted
        }
        path.rewind()
        path.moveTo(line.x0, line.y0)
        path.lineTo(line.x1, line.y1)
        canvas.drawPath(path, paint)
    }
    paint.pathEffect = null
    paint.style = Paint.Style.FILL
    paint.textAlign = Paint.Align.CENTER
    for (text in drawing.texts) {
        paint.textSize = text.size
        paint.typeface = if (text.role == PlanTextRole.Area) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.color = when (text.role) {
            PlanTextRole.Area, PlanTextRole.Dimension -> ink.text
            else -> ink.muted
        }
        val baseline = text.cy - (paint.ascent() + paint.descent()) / 2
        if (text.vertical) {
            canvas.save()
            canvas.rotate(QUARTER_TURN, text.cx, text.cy)
            canvas.drawText(text.text, text.cx, baseline, paint)
            canvas.restore()
        } else {
            canvas.drawText(text.text, text.cx, baseline, paint)
        }
    }
}

// ── The PDF ──────────────────────────────────────────────────────────────────────────────────

/**
 * The survey on A4: the plan at a round scale, the table of measurements, the scan's name and
 * date, the notes and the notice. A room starts at the top of the page and its tables follow it,
 * whatever its size; a room with more walls than the first page holds carries the rest on the
 * pages after it — no wall is left out. Vector — its text selects — and on light paper whatever
 * the app's theme: the ink is the light stage's.
 */
internal object RoomPlanPdf {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
    private const val POINTS_PER_METRE = 2834.65f
    private const val TITLE_Y = 58f
    private const val HEADING_Y = 76f
    private const val RULE_Y = 88f
    private const val PLAN_TOP = 96f
    private const val PLAN_HEIGHT = 388f
    private const val CAPTION_GAP = 18f
    private const val TABLE_GAP = 30f
    private const val ROW = 16f
    private const val VALUE_X = 150f
    private const val ROOM_VALUE_X = 60f
    private const val WALLS_X = 350f
    private const val WALL_VALUE_X = 60f
    private const val FOLLOWING_COLUMNS = 3
    private const val FOOT_Y = 806f
    private const val TITLE_SIZE = 20f
    private const val HEAD_SIZE = 12f
    private const val TEXT_SIZE = 10f
    private const val SMALL_SIZE = 8.5f
    private const val LEGEND_LINE = 22f
    private const val LEGEND_GAP = 6f
    private const val LEGEND_SPACE = 16f
    private val SCALES = intArrayOf(20, 25, 50, 75, 100, 150, 200, 250, 500)

    /** One page being written: its canvas, the paint every mark shares, and the paper's ink. */
    private class Pen(val canvas: Canvas, val paint: Paint, val ink: PlanInk) {
        @Suppress("LongParameterList") // one line of text: where, how big, which ink
        fun text(
            value: String,
            x: Float,
            y: Float,
            size: Float,
            color: Int,
            bold: Boolean = false,
            right: Boolean = false,
        ) {
            paint.style = Paint.Style.FILL
            paint.textSize = size
            paint.color = color
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
            canvas.drawText(value, x, y, paint)
        }
    }

    fun write(out: OutputStream, survey: RoomSurvey, words: PlanWords, title: String, date: String?) {
        val light = StageChrome.Light
        val ink = PlanInk(
            wall = light.onGlass.toArgb(),
            muted = light.onGlassMuted.toArgb(),
            text = light.onGlass.toArgb(),
            fill = light.onGlass.copy(alpha = FLOOR_ALPHA).toArgb(),
        )
        val heading = SurveyReport.heading(words.survey, title, date)
        val document = PdfDocument()
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val notes = SurveyReport.notes(survey, words.survey).flatMap { wrap(it, CONTENT_WIDTH, SMALL_SIZE, paint) }
            // The last baseline a table may use: a line clear of the notes above the notice.
            val tableEnd = FOOT_Y - ROW * (notes.size + 1)
            var number = 1
            var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create())
            var pen = Pen(page.canvas, paint, ink)
            head(pen, words, heading)
            val tableY = plan(pen, survey, words)
            figures(pen, survey, words, tableY)
            val firstRows = max(1, ((tableEnd - tableY) / ROW).toInt())
            val followingRows = ((tableEnd - PLAN_TOP - ROW) / ROW).toInt()
            val walls = SurveyReport.pages(
                SurveyReport.wallRows(survey, words.survey), firstRows, followingRows * FOLLOWING_COLUMNS,
            )
            walls(pen, words.survey.walls, walls.first(), WALLS_X, tableY, firstRows)
            foot(pen, words, notes, number, walls.size)
            document.finishPage(page)
            for (rows in walls.drop(1)) {
                number++
                page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create())
                pen = Pen(page.canvas, paint, ink)
                head(pen, words, heading)
                walls(pen, words.pdfWallsContinued, rows, MARGIN, PLAN_TOP + ROW, followingRows)
                foot(pen, words, notes, number, walls.size)
                document.finishPage(page)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    /** The page's head: what it is, whose scan, and a rule under both. */
    private fun head(pen: Pen, words: PlanWords, heading: String) {
        pen.text(words.pdfTitle, MARGIN, TITLE_Y, TITLE_SIZE, pen.ink.text, bold = true)
        pen.text(words.survey.madeWith, MARGIN + CONTENT_WIDTH, TITLE_Y, TEXT_SIZE, pen.ink.muted, right = true)
        pen.text(heading, MARGIN, HEADING_Y, TEXT_SIZE, pen.ink.muted)
        pen.paint.style = Paint.Style.STROKE
        pen.paint.strokeWidth = HAIR_STROKE
        pen.paint.color = pen.ink.muted
        pen.canvas.drawLine(MARGIN, RULE_Y, MARGIN + CONTENT_WIDTH, RULE_Y, pen.paint)
    }

    /**
     * The plan under the head, with its scale and what a dashed wall means; returns the baseline
     * the tables start on. The plan is laid out in the height a large room needs, then moved up
     * to the head by what it leaves empty above it: a small room leaves no blank half page.
     */
    private fun plan(pen: Pen, survey: RoomSurvey, words: PlanWords): Float {
        if (!survey.hasPlan) {
            val at = PLAN_TOP + ROW * 2
            pen.text(words.emptyTitle, MARGIN, at, HEAD_SIZE, pen.ink.muted)
            return at + TABLE_GAP
        }
        val paint = pen.paint
        val metrics = PlanMetrics(1f) { value, size ->
            paint.textSize = size
            paint.typeface = Typeface.DEFAULT
            paint.measureText(value)
        }
        val drawing = RoomPlanLayout.layout(
            survey, SurveyReport.planTexts(survey, words.survey), CONTENT_WIDTH, PLAN_HEIGHT, metrics,
            scales = SCALES.map { POINTS_PER_METRE / it },
        )
        val tops = drawing.texts.map { it.top } + drawing.lines.map { min(it.y0, it.y1) }
        val bottoms = drawing.texts.map { it.bottom } + drawing.lines.map { max(it.y0, it.y1) }
        val top = tops.minOrNull() ?: 0f
        val bottom = bottoms.maxOrNull() ?: PLAN_HEIGHT
        pen.canvas.save()
        pen.canvas.translate(MARGIN, PLAN_TOP - top)
        drawRoomPlan(pen.canvas, drawing, pen.ink, 1f)
        pen.canvas.restore()
        val caption = PLAN_TOP + (bottom - top) + CAPTION_GAP
        val scale = (POINTS_PER_METRE / drawing.pxPerMetre).roundToInt()
        pen.text(words.pdfScale.format(scale), MARGIN, caption, SMALL_SIZE, pen.ink.muted)
        if (survey.walls.any { !it.seen || it.unseen.isNotEmpty() }) legend(pen, words, caption)
        return caption + TABLE_GAP
    }

    /** The survey's figures from [y] down, then what to scan for what is missing. */
    private fun figures(pen: Pen, survey: RoomSurvey, words: PlanWords, y: Float) {
        var at = y
        pen.text(words.surveyTitle, MARGIN, at, HEAD_SIZE, pen.ink.text, bold = true)
        for (row in SurveyReport.rows(survey, words.survey)) {
            at += ROW
            pen.text(row.label, MARGIN, at, TEXT_SIZE, pen.ink.muted)
            pen.text(row.value, MARGIN + VALUE_X, at, TEXT_SIZE, if (row.muted) pen.ink.muted else pen.ink.text)
        }
        for (hint in SurveyReport.hints(survey, words.survey)) {
            for (line in wrap(hint, WALLS_X - MARGIN - LEGEND_SPACE, TEXT_SIZE, pen.paint)) {
                at += ROW
                pen.text(line, MARGIN, at, TEXT_SIZE, pen.ink.text)
            }
        }
        if (!survey.hasPlan) return
        // Each room's area and volume, then the plan's total, as on the screen.
        at += TABLE_GAP
        pen.text(words.survey.rooms, MARGIN, at, HEAD_SIZE, pen.ink.text, bold = true)
        val rows = SurveyReport.roomRows(SurveyReport.areas(survey, words.survey), words.survey)
        rows.forEachIndexed { i, row ->
            at += ROW
            val total = i == rows.lastIndex
            val ink = if (row.muted) pen.ink.muted else pen.ink.text
            pen.text(row.label, MARGIN, at, TEXT_SIZE, pen.ink.muted, bold = total)
            pen.text(row.value, MARGIN + ROOM_VALUE_X, at, TEXT_SIZE, ink, bold = total)
        }
    }

    /** [rows] of walls under [head], in columns of [perColumn] from ([x], [y]). */
    @Suppress("LongParameterList") // a table: its head, its rows, where it stands and how tall
    private fun walls(pen: Pen, head: String, rows: List<SurveyRow>, x: Float, y: Float, perColumn: Int) {
        if (rows.isEmpty()) return
        pen.text(head, x, y, HEAD_SIZE, pen.ink.text, bold = true)
        val column = CONTENT_WIDTH / FOLLOWING_COLUMNS
        rows.forEachIndexed { i, row ->
            val left = x + (i / perColumn) * column
            val at = y + ROW * (1 + i % perColumn)
            pen.text(row.label, left, at, TEXT_SIZE, pen.ink.muted)
            pen.text(row.value, left + WALL_VALUE_X, at, TEXT_SIZE, if (row.muted) pen.ink.muted else pen.ink.text)
        }
    }

    /** The page's foot: what the marks mean, the notice, and the page's number when there are several. */
    private fun foot(pen: Pen, words: PlanWords, notes: List<String>, number: Int, count: Int) {
        pen.text(words.survey.notice, MARGIN, FOOT_Y, TEXT_SIZE, pen.ink.text, bold = true)
        if (count > 1) {
            val page = words.pdfPage.format(number, count)
            pen.text(page, MARGIN + CONTENT_WIDTH, FOOT_Y, SMALL_SIZE, pen.ink.muted, right = true)
        }
        var at = FOOT_Y
        for (line in notes.reversed()) {
            at -= ROW
            pen.text(line, MARGIN, at, SMALL_SIZE, pen.ink.muted)
        }
    }

    private fun legend(pen: Pen, words: PlanWords, y: Float) {
        val canvas = pen.canvas
        val paint = pen.paint
        val ink = pen.ink
        var x = MARGIN + CONTENT_WIDTH
        val marks = listOf(words.legendUnseen to PlanStroke.WallUnseen, words.legendSeen to PlanStroke.Wall)
        for ((label, stroke) in marks) {
            paint.style = Paint.Style.FILL
            paint.textSize = SMALL_SIZE
            paint.typeface = Typeface.DEFAULT
            paint.textAlign = Paint.Align.RIGHT
            paint.color = ink.muted
            canvas.drawText(label, x, y, paint)
            x -= paint.measureText(label) + LEGEND_GAP
            val at = y - SMALL_SIZE / 3
            val line = PlanLine(x - LEGEND_LINE, at, x, at, stroke)
            val mark = RoomPlanDrawing(0f, 0f, 0f, FloatArray(0), listOf(line), emptyList())
            drawRoomPlan(canvas, mark, ink, 1f)
            x -= LEGEND_LINE + LEGEND_SPACE
        }
    }

    /** [text] broken at spaces into lines no longer than [width]. */
    private fun wrap(text: String, width: Float, size: Float, paint: Paint): List<String> {
        paint.textSize = size
        paint.typeface = Typeface.DEFAULT
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && paint.measureText(next) > width) {
                lines += line
                line = word
            } else {
                line = next
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }
}

/** The survey's PDF, written to the share cache and handed to the share sheet; `false` when it could not be. */
private suspend fun sharePlanPdf(
    context: Context,
    survey: RoomSurvey,
    words: PlanWords,
    title: String,
    date: String?,
): Boolean {
    val uri = writePlanShare(context, words.pdfFile.format(title)) { RoomPlanPdf.write(it, survey, words, title, date) }
        ?: return false
    sharePlanFile(context, uri, PDF_MIME_TYPE, title)
    return true
}

/**
 * A plan file named [name], written by [write] to the share cache off the main thread, and the
 * URI the share sheet reads it through; `null` when it could not be written or given a URI, for
 * the caller to say so rather than crash. A file that fails half-written is deleted: no app is
 * handed it, and the cache does not keep it.
 */
internal suspend fun writePlanShare(context: Context, name: String, write: (OutputStream) -> Unit): Uri? =
    withContext(Dispatchers.IO) {
        val file = runCatching { planShareFile(context, name) }.getOrNull() ?: return@withContext null
        runCatching {
            file.outputStream().use(write)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.onFailure { file.delete() }.getOrNull()
    }

/**
 * A fresh file named [name] in the plan's share cache, in the folder of its kind, its extension:
 * `plan/pdf/`, `plan/dxf/`. Each kind keeps its latest file only: a new PDF replaces the last
 * one, which its share sheet has read by then, and leaves the DXF be, which an app may still be
 * reading — so the cache never holds more than a file of each kind. Anything else under `plan/`,
 * such as the one-folder-per-share an earlier build left there, goes with the next share.
 */
internal fun planShareFile(context: Context, name: String): File {
    val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "-")
    val kind = safe.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT).ifEmpty { "file" }
    val plan = rerunShareDirectory(context, RERUN_SHARE_PLAN)
    plan.listFiles()?.filter { it.name !in PLAN_SHARE_KINDS }?.forEach { it.deleteRecursively() }
    val folder = File(plan, kind)
    folder.deleteRecursively()
    val dir = File(folder, UUID.randomUUID().toString()).apply { mkdirs() }
    return File(dir, safe)
}

/** The kinds of plan file shared, each in its own folder under `plan/`. */
private val PLAN_SHARE_KINDS = setOf("pdf", "dxf")

/**
 * The file at [uri] alone handed to the share sheet as [mimeType]: one [Intent.ACTION_SEND],
 * readable by the app it reaches, offered to every app that takes [mimeType].
 */
internal fun sharePlanFile(context: Context, uri: Uri, mimeType: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        clipData = ClipData.newRawUri(uri.lastPathSegment, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** The survey's few lines, handed to the share sheet as plain text. */
private fun sharePlanText(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ── QA: a synthetic stored scan ──────────────────────────────────────────────────────────────

/**
 * A scan of the ray-cast room ([RerunSyntheticRoom]) as the recorder would save it: the camera's
 * path and the dense cloud its depth frames fuse into. The emulator cannot scan, and no real
 * room belongs in a capture. With [partial] the scan never saw one wall nor the ceiling. Another
 * [scene], walked along [walk], is a second room of a whole home (#4647).
 */
internal suspend fun syntheticPlanCapture(
    partial: Boolean,
    scene: SyntheticScene = RerunSyntheticRoom.scene,
    walk: () -> List<DebugPose> = { RerunSyntheticRoom.poses() },
): RerunCapturePack {
    val (events, cloud) = withContext(Dispatchers.Default) {
        val poses = walk()
        val fusion = DenseFusion()
        poses.forEach { fusion.add(DepthBackProjection.project(scene.render(it))) }
        val whole = fusion.cloud()
        val events = poses.mapIndexed { i, pose -> ArDebugEvent.CameraPose(i * QA_FRAME_NANOS, pose) }
        events to if (partial) whole.without { x, y, _ -> x > QA_HIDDEN_WALL_X || y > QA_HIDDEN_ABOVE_Y } else whole
    }
    val device = ScanDevice("android", "Synthetic", ScanDevice.TIER_DEPTH, ScanDevice.SOURCE_RAW_DEPTH)
    return RerunCaptureBuilder.build(events, ReplayLens.Default, emptyList(), device, cloud)
}

/** The cloud without the surfels [hidden] says the scan never saw. */
private fun DenseCloud.without(hidden: (Float, Float, Float) -> Boolean): DenseCloud {
    val kept = (0 until count).filter { !hidden(positions[it * 3], positions[it * 3 + 1], positions[it * 3 + 2]) }
    fun floats(from: FloatArray) = FloatArray(kept.size * 3) { from[kept[it / 3] * 3 + it % 3] }
    return DenseCloud(
        positions = floats(positions),
        colors = IntArray(kept.size) { colors[kept[it]] },
        normals = normals?.let(::floats),
        confidences = confidences?.let { all -> ByteArray(kept.size) { all[kept[it]] } },
    )
}

internal const val RERUN_PLAN_TAG = "rerun-plan"
internal const val RERUN_PLAN_DRAWING_TAG = "rerun-plan-drawing"
internal const val RERUN_PLAN_HINTS_TAG = "rerun-plan-hints"
internal const val RERUN_PLAN_ROOMS_TAG = "rerun-plan-rooms"
internal const val RERUN_PLAN_SHARES_TAG = "rerun-plan-shares"
internal const val RERUN_PLAN_SHARE_PDF_TAG = "rerun-plan-share-pdf"
internal const val RERUN_PLAN_SHARE_DXF_TAG = "rerun-plan-share-dxf"
internal const val RERUN_PLAN_SHARE_TEXT_TAG = "rerun-plan-share-text"

/** The title QA's synthetic scan is kept under. */
internal const val QA_PLAN_TITLE = "Synthetic room"

private const val PLAN_SHARE = 1.3f
private const val MAX_PLAN_FONT_SCALE = 1.3f
private const val MIN_PLAN_SIDE_M = 0.5f
private const val MIN_PLAN_RATIO = 1f
private const val MAX_PLAN_RATIO = 1.6f
private const val FLOOR_ALPHA = 0.06f
private const val WALL_STROKE = 2.5f
private const val UNSEEN_STROKE = 1.5f
private const val TICK_STROKE = 1.25f
private const val HAIR_STROKE = 0.6f
private const val GRID_MINOR_STROKE = 1f
private const val GRID_MAJOR_STROKE = 1.5f
private const val ZONE_STROKE = 1.5f
private const val DASH_ON = 5f
private const val DASH_OFF = 4f
private const val QUARTER_TURN = -90f
private const val MIN_OUTLINE_FLOATS = 6
private const val MILLIS_PER_SECOND = 1000L
private const val PDF_MIME_TYPE = "application/pdf"
private const val QA_FRAME_NANOS = 33_000_000L
private const val QA_HIDDEN_WALL_X = 1.9f
private const val QA_HIDDEN_ABOVE_Y = 2.4f
