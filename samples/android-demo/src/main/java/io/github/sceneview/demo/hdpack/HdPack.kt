package io.github.sceneview.demo.hdpack

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.io.File
import java.io.IOException

/**
 * What the pack is doing, as the Settings row and the viewer pill show it.
 *
 * [WaitingForWifi] is the prefetch parked on its `UNMETERED` constraint — the state of a fresh
 * install on mobile data or with no network. [WaitingForNetwork] is a "Download now" the user
 * asked for (any network) that has no network yet. [NotDownloaded] is nothing queued: the user
 * removed the pack, or the downloads failed for good.
 */
sealed interface HdPackStatus {
    data object Ready : HdPackStatus
    data class Downloading(val fraction: Float) : HdPackStatus
    data object WaitingForWifi : HdPackStatus
    data object WaitingForNetwork : HdPackStatus
    data object NotDownloaded : HdPackStatus
}

/**
 * Process entry point of the HD pack: the bundled manifest, the store, and the WorkManager
 * jobs that fill it.
 *
 * - [schedulePrefetch] runs on every launch. It queues one unique job constrained to an
 *   unmetered network, so a fresh install downloads the pack the first time it sees Wi-Fi and
 *   never on mobile data. It does nothing once the pack is complete or after the user removed it.
 * - [downloadNow] is the user's explicit "Download now": any network, the size having been shown
 *   first by the caller.
 * - [remove] cancels the job, deletes the files and remembers the choice, so the next launch
 *   does not quietly download it again.
 */
object HdPack {
    private const val TAG = "HdPack"
    private const val UNIQUE_WORK = "hd-pack-download"
    private const val PREFS = "hd_pack"
    private const val PREF_REMOVED = "removed_by_user"
    private const val DIR_NAME = "hd-pack"
    private const val MAX_ATTEMPTS = 8

    @Volatile private var store: HdPackStore? = null
    @Volatile private var manifestFailed = false

    /** `true` once the pack has been removed by the user; flips back on "Download now". */
    val removedByUser = MutableStateFlow(false)

    /** The store, or `null` when the bundled manifest is missing or unreadable (feature hidden). */
    fun store(context: Context): HdPackStore? {
        store?.let { return it }
        if (manifestFailed) return null
        return synchronized(this) {
            store ?: runCatching {
                val app = context.applicationContext
                val text = app.assets.open(HdPackManifest.MANIFEST_ASSET).bufferedReader().use { it.readText() }
                removedByUser.value = prefs(app).getBoolean(PREF_REMOVED, false)
                HdPackStore(HdPackManifest.parse(text), File(app.filesDir, DIR_NAME))
            }.onFailure {
                manifestFailed = true
                Log.w(TAG, "HD pack disabled: bundled manifest unreadable", it)
            }.getOrNull().also { store = it }
        }
    }

    /** Queues the Wi-Fi-only prefetch unless the pack is complete or the user removed it. */
    fun schedulePrefetch(context: Context) {
        val store = store(context) ?: return
        if (store.isComplete || removedByUser.value) return
        enqueue(context, NetworkType.UNMETERED, ExistingWorkPolicy.KEEP)
    }

    /** The user's "Download now" — any network. */
    fun downloadNow(context: Context) {
        store(context) ?: return
        setRemoved(context, false)
        enqueue(context, NetworkType.CONNECTED, ExistingWorkPolicy.REPLACE)
    }

    /** Cancels any download, deletes the pack and remembers the choice. Returns the bytes freed. */
    suspend fun remove(context: Context): Long {
        val store = store(context) ?: return 0L
        setRemoved(context, true)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        return store.removeAll()
    }

    /** Live [HdPackStatus] of the whole pack. */
    fun status(context: Context, store: HdPackStore): Flow<HdPackStatus> = combine(
        store.readyIds,
        store.transfer,
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_WORK),
    ) { ready, transfer, infos ->
        statusOf(ready.size == store.manifest.assets.size, transfer, infos.firstOrNull { !it.state.isFinished })
    }

    internal fun statusOf(complete: Boolean, transfer: HdTransfer?, pending: WorkInfo?): HdPackStatus = when {
        complete -> HdPackStatus.Ready
        transfer != null -> HdPackStatus.Downloading(transfer.fraction)
        pending == null -> HdPackStatus.NotDownloaded
        pending.state == WorkInfo.State.RUNNING -> HdPackStatus.Downloading(0f)
        pending.constraints.requiredNetworkType == NetworkType.UNMETERED -> HdPackStatus.WaitingForWifi
        else -> HdPackStatus.WaitingForNetwork
    }

    private fun enqueue(context: Context, network: NetworkType, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<HdPackWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(network)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, policy, request)
    }

    private fun setRemoved(context: Context, removed: Boolean) {
        removedByUser.value = removed
        prefs(context).edit().putBoolean(PREF_REMOVED, removed).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Downloads what is missing; a network failure retries with WorkManager's backoff. */
    class HdPackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val store = store(applicationContext) ?: return Result.failure()
            return try {
                store.downloadMissing()
                Result.success()
            } catch (e: IOException) {
                Log.w(TAG, "HD pack download attempt $runAttemptCount failed", e)
                if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            }
        }
    }
}
