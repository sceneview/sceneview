package io.github.sceneview.demo.hdpack

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.io.File
import java.io.IOException

/**
 * What the pack is doing, as the Settings row and the viewer pill show it.
 *
 * [WaitingForWifi] is the prefetch parked on its `UNMETERED` constraint — the state of a fresh
 * install on mobile data or with no network. [WaitingForNetwork] is a "Download now" the user
 * asked for (any network) that has no network yet. [NotDownloaded] is nothing queued: the user
 * removed the pack. [Failed] is the last job giving up — out of retries, or a file that does not
 * match its manifest hash.
 */
sealed interface HdPackStatus {
    data object Ready : HdPackStatus
    data class Downloading(val fraction: Float) : HdPackStatus
    data object WaitingForWifi : HdPackStatus
    data object WaitingForNetwork : HdPackStatus

    /** One model's job is running, but another model's file is on the wire first. */
    data object Queued : HdPackStatus
    data object NotDownloaded : HdPackStatus
    data object Failed : HdPackStatus
}

/**
 * Process entry point of the HD pack: the bundled manifest, the store, and the WorkManager
 * jobs that fill it.
 *
 * - [schedulePrefetch] runs on every launch. It queues one unique job constrained to an
 *   unmetered network for [PREFETCH_IDS] only (Flight Helmet), so a fresh install gets that model
 *   the first time it sees Wi-Fi and never on mobile data. Every other model downloads only when
 *   the user taps its pill. It does nothing once those files are in or after the user removed the pack.
 * - [downloadNow] is the user's explicit "Download now": any network, the size having been shown
 *   first by the caller. With an asset id it fetches that one model (the viewer pill); without,
 *   every missing file (About).
 * - [remove] cancels the job, deletes the files and remembers the choice, so the next launch
 *   does not quietly download it again.
 */
object HdPack {
    private const val TAG = "HdPack"
    /** The pre-per-model job, which fetched the whole pack on Wi-Fi: cancelled on launch. */
    private const val LEGACY_WORK = "hd-pack-download"
    private const val PREFETCH_WORK = "hd-pack-prefetch"
    private const val ALL_WORK = "hd-pack-all"
    private const val ASSET_WORK_PREFIX = "hd-pack-asset-"
    private const val TAG_PACK = "hd-pack"
    private const val TAG_ASSET_PREFIX = "hd-pack-asset:"
    private const val PREFS = "hd_pack"
    private const val PREF_REMOVED = "removed_by_user"
    private const val DIR_NAME = "hd-pack"
    private const val MAX_ATTEMPTS = 8
    private const val KEY_ONLY = "only"

    /** What the Wi-Fi prefetch fetches on its own. Every other model waits for its pill's tap. */
    val PREFETCH_IDS: Set<String> = setOf("flight-helmet")

    private val _loaded = MutableStateFlow<HdPackStore?>(null)
    @Volatile private var manifestFailed = false

    /** `true` once the pack has been removed by the user; flips back on the whole-pack "Download now" (About), not on one model's pill. */
    val removedByUser = MutableStateFlow(false)

    /**
     * The store once [store] has loaded it, `null` before — never touches the disk, safe to
     * collect on the main thread. Every screen observes the same load, whoever triggered it.
     */
    val loaded: StateFlow<HdPackStore?> = _loaded.asStateFlow()

    /**
     * The store, or `null` when the bundled manifest is missing or unreadable (feature hidden).
     * The first call reads the manifest asset and scans the pack directory: call it off the main
     * thread (`MainActivity` warms it on `Dispatchers.IO`).
     */
    fun store(context: Context): HdPackStore? {
        _loaded.value?.let { return it }
        if (manifestFailed) return null
        return synchronized(this) {
            _loaded.value ?: runCatching {
                val app = context.applicationContext
                val text = app.assets.open(HdPackManifest.MANIFEST_ASSET).bufferedReader().use { it.readText() }
                removedByUser.value = prefs(app).getBoolean(PREF_REMOVED, false)
                HdPackStore(HdPackManifest.parse(text), File(app.filesDir, DIR_NAME))
            }.onFailure {
                manifestFailed = true
                Log.w(TAG, "HD pack disabled: bundled manifest unreadable", it)
            }.getOrNull().also { _loaded.value = it }
        }
    }

    /** Queues the Wi-Fi-only prefetch of [PREFETCH_IDS] unless they are in or the user removed the pack. */
    fun schedulePrefetch(context: Context) {
        val store = store(context) ?: return
        // A job queued by an earlier build would still fetch the whole pack on Wi-Fi.
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_WORK)
        val prefetch = PREFETCH_IDS.filterTo(mutableSetOf()) { store.manifest.asset(it) != null }
        if (prefetch.isEmpty() || store.isReady(prefetch) || removedByUser.value) return
        enqueue(context, PREFETCH_WORK, NetworkType.UNMETERED, ExistingWorkPolicy.KEEP, prefetch)
    }

    /**
     * The user's "Download now" — any network. With [assetId], that one model only (a second tap
     * while it is queued keeps the job already there); without, every missing file.
     */
    fun downloadNow(context: Context, assetId: String? = null) {
        val store = store(context) ?: return
        // One model's pill fetches that model and nothing more: it does not turn the Wi-Fi
        // prefetch back on after the user removed the pack. Only the whole-pack download does.
        if (assetId == null) setRemoved(context, false)
        if (assetId != null && store.manifest.asset(assetId) != null) {
            val name = ASSET_WORK_PREFIX + assetId
            enqueue(context, name, NetworkType.CONNECTED, ExistingWorkPolicy.KEEP, setOf(assetId))
        } else {
            val all = store.manifest.assets.mapTo(mutableSetOf()) { it.id }
            enqueue(context, ALL_WORK, NetworkType.CONNECTED, ExistingWorkPolicy.REPLACE, all)
        }
    }

    /** Cancels any download, deletes the pack and remembers the choice. Returns the bytes freed. */
    suspend fun remove(context: Context): Long {
        val store = store(context) ?: return 0L
        setRemoved(context, true)
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG_PACK)
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_WORK)
        return store.removeAll()
    }

    /**
     * Live [HdPackStatus] of the whole pack (About → App). It reads the pack's own jobs only — the
     * whole-pack download and the Wi-Fi prefetch — so one model's failed pill does not turn the
     * whole pack to "Failed".
     */
    fun status(context: Context, store: HdPackStore): Flow<HdPackStatus> = combine(
        store.readyIds,
        store.transfer,
        WorkManager.getInstance(context).getWorkInfosFlow(
            WorkQuery.fromUniqueWorkNames(listOf(ALL_WORK, PREFETCH_WORK)),
        ),
    ) { ready, transfer, infos ->
        statusOf(ready.size == store.manifest.assets.size, transfer, relevantJob(infos))
    }

    /** Live [HdPackStatus] of one model's file — what its viewer pill narrates. */
    fun assetStatus(context: Context, store: HdPackStore, assetId: String): Flow<HdPackStatus> = combine(
        store.readyIds,
        store.transfer,
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG_ASSET_PREFIX + assetId),
    ) { ready, transfer, infos ->
        assetStatusOf(assetId, ready, transfer, relevantJob(infos))
    }

    /** The job that speaks for a set: a running one, else a pending one, else one that gave up. */
    private fun relevantJob(infos: List<WorkInfo>): WorkInfo? =
        infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?: infos.firstOrNull { !it.state.isFinished }
            ?: infos.firstOrNull { it.state == WorkInfo.State.FAILED }

    /**
     * One model's status. [work] is the job covering it (see [relevantJob]). A running job whose
     * file is not the one on the wire is waiting for the store's lock behind another model.
     */
    internal fun assetStatusOf(
        assetId: String,
        ready: Set<String>,
        transfer: HdTransfer?,
        work: WorkInfo?,
    ): HdPackStatus = when {
        assetId in ready -> HdPackStatus.Ready
        transfer?.assetId == assetId -> HdPackStatus.Downloading(transfer.fractionOf(assetId) ?: 0f)
        work == null -> HdPackStatus.NotDownloaded
        work.state == WorkInfo.State.FAILED -> HdPackStatus.Failed
        work.state.isFinished -> HdPackStatus.NotDownloaded
        work.state == WorkInfo.State.RUNNING -> HdPackStatus.Queued
        work.constraints.requiredNetworkType == NetworkType.UNMETERED -> HdPackStatus.WaitingForWifi
        else -> HdPackStatus.WaitingForNetwork
    }

    /** [work] is the unique job still pending, else the last one that finished. */
    internal fun statusOf(complete: Boolean, transfer: HdTransfer?, work: WorkInfo?): HdPackStatus = when {
        complete -> HdPackStatus.Ready
        transfer != null -> HdPackStatus.Downloading(transfer.fraction)
        work == null -> HdPackStatus.NotDownloaded
        work.state == WorkInfo.State.FAILED -> HdPackStatus.Failed
        work.state.isFinished -> HdPackStatus.NotDownloaded
        work.state == WorkInfo.State.RUNNING -> HdPackStatus.Downloading(0f)
        work.constraints.requiredNetworkType == NetworkType.UNMETERED -> HdPackStatus.WaitingForWifi
        else -> HdPackStatus.WaitingForNetwork
    }

    private fun enqueue(
        context: Context,
        name: String,
        network: NetworkType,
        policy: ExistingWorkPolicy,
        ids: Set<String>,
    ) {
        val request = OneTimeWorkRequestBuilder<HdPackWorker>()
            .setInputData(inputFor(ids))
            .addTag(TAG_PACK)
            .apply { ids.forEach { addTag(TAG_ASSET_PREFIX + it) } }
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(network)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
    }

    /** The worker input carrying the ids a job fetches. */
    internal fun inputFor(ids: Set<String>): Data = workDataOf(KEY_ONLY to ids.toTypedArray())

    /**
     * The ids a job fetches. A job queued by an earlier build carries no id list: it was the Wi-Fi
     * prefetch, so it fetches what the prefetch fetches now ([PREFETCH_IDS]), never the whole pack.
     */
    internal fun idsToFetch(input: Data): Set<String> = input.getStringArray(KEY_ONLY)?.toSet() ?: PREFETCH_IDS

    private fun setRemoved(context: Context, removed: Boolean) {
        removedByUser.value = removed
        prefs(context).edit { putBoolean(PREF_REMOVED, removed) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Downloads the files its job was queued for. A network failure retries with WorkManager's backoff; a file that
     * does not match its manifest hash fails at once (its `.part` is already deleted), since
     * fetching the same bytes again would only fail again.
     */
    class HdPackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val store = store(applicationContext) ?: return Result.failure()
            return try {
                store.downloadMissing(only = idsToFetch(inputData))
                Result.success()
            } catch (e: HdPackIntegrityException) {
                Log.e(TAG, "HD pack file rejected, not retrying", e)
                Result.failure()
            } catch (e: IOException) {
                Log.w(TAG, "HD pack download attempt $runAttemptCount failed", e)
                if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            }
        }
    }
}
