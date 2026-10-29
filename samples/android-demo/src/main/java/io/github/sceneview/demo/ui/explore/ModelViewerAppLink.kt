package io.github.sceneview.demo.ui.explore

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import io.github.sceneview.demo.sources.GalleryModel
import io.github.sceneview.demo.sources.ModelSourceId
import io.github.sceneview.demo.telemetry.logOutboundLink

/**
 * The hand-off from "Browse online models" to the 3D AR Model Viewer app (#4622).
 *
 * The viewer claims `https://sketchfab.com/3d-models/…` links and reads the model's uid from
 * the last `-` segment of the path (`AppLink.parse` in the viewer's repository), so a link
 * spelled with the bare uid opens that very model there. Only Sketchfab models can be handed
 * over: a Poly Haven asset is a multi-file glTF the viewer has no link for, and an Icosa model
 * keeps no page URL on [GalleryModel].
 */
object ModelViewerAppLink {
    /** The viewer's application id — the `viewer` flavour of `ar-model-viewer-android`. */
    const val PACKAGE = "com.gorisse.thomas.arcamera"

    /** Its Play listing, in the Play Store app, then on the web. */
    const val MARKET_URL = "market://details?id=$PACKAGE"
    const val PLAY_WEB_URL = "https://play.google.com/store/apps/details?id=$PACKAGE"

    /** A Sketchfab uid: 32 lowercase hex characters. */
    private val SKETCHFAB_UID = Regex("^[0-9a-f]{32}$")

    /**
     * The link that opens [model] in the viewer, or `null` when the viewer cannot open it —
     * any source but Sketchfab, or an id that is not a Sketchfab uid.
     */
    fun modelLink(model: GalleryModel): String? =
        model.id.takeIf { model.sourceId == ModelSourceId.SKETCHFAB && SKETCHFAB_UID.matches(it) }
            ?.let { "https://sketchfab.com/3d-models/$it" }

    /**
     * What the viewer screen offers for [model]: open it in the installed app, send the user
     * to the app's Play listing, or nothing when the viewer could not open this model anyway
     * (a store page for an app that then cannot show the model would be a dead end).
     */
    fun handoff(model: GalleryModel, appInstalled: Boolean): ModelViewerHandoff? {
        val link = modelLink(model) ?: return null
        return if (appInstalled) ModelViewerHandoff.OpenInApp(link) else ModelViewerHandoff.GetApp
    }
}

/** The one action the gallery viewer shows towards the 3D AR Model Viewer app. */
sealed interface ModelViewerHandoff {
    /** The app is installed: open [link] in it. */
    data class OpenInApp(val link: String) : ModelViewerHandoff

    /** The app is not installed: open its Play listing. */
    data object GetApp : ModelViewerHandoff
}

/**
 * True when the viewer app is installed. Visible to this app through the manifest's
 * `<queries>` entry for [ModelViewerAppLink.PACKAGE]; without it the answer is always `false`.
 */
internal fun Context.isModelViewerAppInstalled(): Boolean =
    runCatching { packageManager.getPackageInfo(ModelViewerAppLink.PACKAGE, 0) }.isSuccess

/**
 * Runs [handoff]. Opening is pinned to the viewer's package, so the link never lands in a
 * browser or on the Sketchfab app instead; the listing tries the Play Store app, then the web.
 * Does nothing rather than crash if no target resolves.
 */
internal fun Context.launchModelViewerHandoff(handoff: ModelViewerHandoff) {
    val targets = when (handoff) {
        is ModelViewerHandoff.OpenInApp -> listOf(
            Intent(Intent.ACTION_VIEW, handoff.link.toUri())
                .setPackage(ModelViewerAppLink.PACKAGE)
                .addCategory(Intent.CATEGORY_BROWSABLE),
        )
        ModelViewerHandoff.GetApp -> listOf(
            Intent(Intent.ACTION_VIEW, ModelViewerAppLink.MARKET_URL.toUri()),
            Intent(Intent.ACTION_VIEW, ModelViewerAppLink.PLAY_WEB_URL.toUri()),
        )
    }
    logOutboundLink(
        when (handoff) {
            is ModelViewerHandoff.OpenInApp -> handoff.link
            ModelViewerHandoff.GetApp -> ModelViewerAppLink.MARKET_URL
        },
    )
    for (intent in targets) {
        if (runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
    Log.w(HANDOFF_LOG_TAG, "Nothing could open the 3D AR Model Viewer hand-off (#4622).")
}

private const val HANDOFF_LOG_TAG = "ModelViewerHandoff"
