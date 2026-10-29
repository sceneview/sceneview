package io.github.sceneview.demo.demos

import android.content.Context
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.MeasureUnitSystem
import io.github.sceneview.demo.demos.internal.RoomPlanDxf
import io.github.sceneview.demo.demos.internal.RoomSurvey

/**
 * The survey's plan as a DXF drawing, for a CAD tool to take it up, written to the share cache and
 * handed to the share sheet alone, typed [DXF_MIME_TYPE]; `false` when the survey has no plan to
 * draw or the drawing could not be written. The drawing is named after the PDF of the same plan.
 */
internal suspend fun sharePlanDxf(context: Context, survey: RoomSurvey, words: PlanWords, title: String): Boolean {
    if (!survey.hasPlan) return false
    val drawing = planDxfWords(context, survey, words, title)
    val name = words.pdfFile.format(title).substringBeforeLast('.') + DXF_EXTENSION
    val uri = writePlanShare(context, name) { RoomPlanDxf.write(it, survey, words.survey, drawing) } ?: return false
    sharePlanFile(context, uri, DXF_MIME_TYPE, title)
    return true
}

/** The drawing's words: its openings' names and the lines under the plan, the warning to check it on site first among the notes. */
private fun planDxfWords(context: Context, survey: RoomSurvey, words: PlanWords, title: String) = RoomPlanDxf.Words(
    legend = listOfNotNull(
        context.getString(R.string.demo_ar_rerun_plan_dxf_heading, title).takeIf { title.isNotBlank() },
        context.getString(R.string.demo_ar_rerun_plan_dxf_verify),
        context.getString(
            when (words.survey.units) {
                MeasureUnitSystem.Metric -> R.string.demo_ar_rerun_plan_dxf_units
                MeasureUnitSystem.Imperial -> R.string.demo_ar_rerun_plan_dxf_units_feet
            },
        ),
        context.getString(R.string.demo_ar_rerun_plan_dxf_estimates).takeIf { survey.hasEstimates },
        words.survey.spreadNote.takeIf { survey.hasSpread },
        words.survey.madeWith,
    ),
    door = context.getString(R.string.demo_ar_rerun_plan_dxf_door),
    window = context.getString(R.string.demo_ar_rerun_plan_dxf_window),
    sill = context.getString(R.string.demo_ar_rerun_plan_dxf_sill),
)

private const val DXF_EXTENSION = ".dxf"

/**
 * The drawing's type. The registered `image/vnd.dxf` would file it among images, and the share
 * sheet would offer galleries and photo editors that cannot read it; Android's own type table has
 * no DXF entry to borrow. `application/dxf`, the type in common use for DXF files, keeps it among
 * documents: mail, cloud drives and file managers take it, and so do the CAD viewers that list it.
 */
private const val DXF_MIME_TYPE = "application/dxf"
