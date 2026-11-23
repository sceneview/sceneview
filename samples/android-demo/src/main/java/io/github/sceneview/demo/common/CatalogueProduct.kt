package io.github.sceneview.demo.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.sceneview.demo.R
import io.github.sceneview.demo.hdpack.HdAsset
import io.github.sceneview.demo.hdpack.HdPack
import io.github.sceneview.demo.hdpack.HdPackDownloadDialog
import io.github.sceneview.demo.hdpack.HdPackStatus
import io.github.sceneview.demo.hdpack.hdPackSize
import io.github.sceneview.demo.hdpack.rememberHdAssetStatus
import io.github.sceneview.demo.hdpack.rememberHdPackStore
import io.github.sceneview.demo.ui.GlassActionPill
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.restPoseBoundingBox
import io.github.sceneview.rememberModelInstance
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Real everyday products the AR demos place, by their HD pack id — the same files the Model
 * Viewer's catalogue shelves and the website's `/view` open (#4560, #4569). The demos used to
 * place Khronos sample assets (the Toy Car); a real product at its real size is what a viewer
 * expects to see in their room.
 */
object CatalogueProducts {
    /** Countertop size (~33 cm). Its SMEG lettering keeps it off every demo's own stage (#5247). */
    const val SMEG_TOASTER = "smeg-toaster"

    /** The Model Viewer's sneaker. A Nike product: never a demo's own subject (#5247). */
    const val AIR_JORDAN_1 = "air-jordan-1"

    /** A full-size sofa on the floor: the plane renderer's floor, at the scale of a living room. */
    const val IKEA_NOCKEBY_SOFA = "ikea-nockeby-sofa"

    /** A countertop appliance that automatic placement can set on a table or a floor alike. */
    const val KITCHENAID_STAND_MIXER = "kitchenaid-stand-mixer"

    /**
     * The products whose HD pack file also ships in the APK (2026-11-09), byte for byte, at
     * [bundledAssetPath]: the Model Viewer's own list and the AR picker show them, so a demo
     * that places one opens it at once instead of downloading the same bytes again.
     */
    val BUNDLED: Set<String> = setOf(
        "peugeot-2008", "vespa-et3", "rivet-aiden-armchair", "rivet-sloane-sofa",
        "rivet-arc-floor-lamp", "wishbone-chair", SMEG_TOASTER, KITCHENAID_STAND_MIXER,
        AIR_JORDAN_1, "canon-at1",
    )

    /** `assets/`-relative path of a [BUNDLED] product (`models/<id with underscores>.glb`), else `null`. */
    fun bundledAssetPath(assetId: String): String? =
        if (assetId in BUNDLED) "models/${assetId.replace('-', '_')}.glb" else null
}

/**
 * One catalogue product as an AR demo uses it: its HD pack entry, the loaded model once its
 * file is on the device, and that file's download status for [CatalogueProductPill].
 *
 * @property realSizeMetres the model's longest bounding-box axis in metres (the manifest's
 *   metres-per-unit [HdAsset.scale] applied): pass it as `scaleToUnits` to show the product at
 *   its real size. `null` until the model is loaded.
 */
@Stable
class CatalogueProduct internal constructor(
    val asset: HdAsset?,
    val instance: ModelInstance?,
    val status: HdPackStatus,
    val fileOnDevice: Boolean,
    private val fixedSizeMetres: Float? = null,
) {
    val realSizeMetres: Float?
        get() = instance?.let { model ->
            fixedSizeMetres ?: run {
                val half = model.asset.restPoseBoundingBox.halfExtent
                maxOf(half[0], half[1], half[2]) * 2f * (asset?.scale ?: 1f)
            }
        }

    /** The pill has something to say: the file is not in yet, or is still loading. */
    val needsPill: Boolean get() = asset != null && instance == null
}

/**
 * Loads the catalogue product [assetId]: from the APK when it is one of
 * [CatalogueProducts.BUNDLED], else from the HD pack — if its file is not on the device yet,
 * it is fetched on Wi-Fi the way the Model Viewer fetches a picked catalogue model ([HdPack.fetchOnWifi]); on mobile data [CatalogueProductPill] offers
 * "download 3 MB", size first.
 */
@Composable
fun rememberCatalogueProduct(modelLoader: ModelLoader, assetId: String): CatalogueProduct {
    val store = rememberHdPackStore()
    val asset = remember(store, assetId) { store?.manifest?.asset(assetId) }
    // A bundled product reads the APK's copy: no fetch, no pill beyond "loading".
    CatalogueProducts.bundledAssetPath(assetId)?.let { path ->
        val instance = rememberModelInstance(modelLoader, path)
        return CatalogueProduct(asset, instance, HdPackStatus.Ready, fileOnDevice = true)
    }
    val context = LocalContext.current
    val readyIds by remember(store) { store?.readyIds ?: MutableStateFlow(emptySet<String>()) }
        .collectAsState()
    val status by rememberHdAssetStatus(store, assetId)
    val file = asset?.takeIf { it.id in readyIds }?.let { store?.fileFor(it) }
    LaunchedEffect(store, asset) {
        if (asset != null && file == null) HdPack.fetchOnWifi(context, asset.id)
    }
    val instance = rememberFileModelInstance(modelLoader, file)
    return CatalogueProduct(asset, instance, status, fileOnDevice = file != null)
}

/**
 * A bundled model that is not a catalogue product, shown at [realSizeMetres] (its longest side)
 * the way [rememberCatalogueProduct] shows a product. It needs no download, so
 * [CatalogueProductPill] stays silent on it.
 *
 * For a demo's own subject that must carry no third-party trademark (#5247): every bundled
 * catalogue product is a branded one, or a piece of furniture too big for a tabletop.
 */
@Composable
fun rememberBundledSubject(modelLoader: ModelLoader, assetPath: String, realSizeMetres: Float): CatalogueProduct {
    val instance = rememberModelInstance(modelLoader, assetPath)
    return CatalogueProduct(
        asset = null,
        instance = instance,
        status = HdPackStatus.Ready,
        fileOnDevice = true,
        fixedSizeMetres = realSizeMetres,
    )
}

/**
 * While [product] is not in the scene, names it and says how far its download is ("SMEG
 * toaster · downloading 34 %"), in the Model Viewer's HD pill anatomy. A tap when nothing is
 * running asks to download it now, size first. Emits nothing once the model is loaded.
 */
@Composable
fun CatalogueProductPill(product: CatalogueProduct) {
    val asset = product.asset ?: return
    if (!product.needsPill) return
    val context = LocalContext.current
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    val status = product.status
    val failed = !product.fileOnDevice && status == HdPackStatus.Failed
    val busy = !failed && (
        product.fileOnDevice || status is HdPackStatus.Downloading || status == HdPackStatus.Queued
        )
    val fraction = (status as? HdPackStatus.Downloading)?.fraction
    val label = when {
        failed -> stringResource(R.string.hd_pill_failed)
        product.fileOnDevice -> stringResource(R.string.hd_pill_loading)
        status == HdPackStatus.Queued -> stringResource(R.string.hd_pill_queued)
        status is HdPackStatus.Downloading ->
            stringResource(R.string.hd_pill_downloading, ((fraction ?: 0f) * 100).toInt())
        status == HdPackStatus.WaitingForWifi -> stringResource(R.string.hd_pill_waiting_wifi)
        status == HdPackStatus.WaitingForNetwork -> stringResource(R.string.hd_pill_waiting_network)
        else -> stringResource(R.string.hd_pill_download, hdPackSize(context, asset.bytes))
    }
    val spoken = stringResource(R.string.glass_pill_subject_label, asset.title, label)
    GlassActionPill(
        icon = Icons.Outlined.HighQuality,
        subject = asset.title,
        label = label,
        onClick = { if (!busy) dialogOpen = true },
        loading = busy,
        progress = fraction?.takeIf { !product.fileOnDevice },
        contentDescription = if (busy) spoken else stringResource(R.string.hd_pill_hint, spoken),
    )
    if (dialogOpen) {
        HdPackDownloadDialog(
            totalBytes = asset.bytes,
            onConfirm = {
                dialogOpen = false
                HdPack.downloadNow(
                    context,
                    assetId = asset.id,
                    // A Wi-Fi fetch parked on mobile data gives way to "now, any network".
                    replaceWifiWait = status == HdPackStatus.WaitingForWifi,
                )
            },
            onDismiss = { dialogOpen = false },
        )
    }
}
