package io.github.sceneview.demo.support

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import io.github.sceneview.demo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** One of the three contribution amounts, as a Play one-time product. */
enum class SupportTier(val productId: String, val labelRes: Int) {
    Small("support_small", R.string.support_tier_small),
    Medium("support_medium", R.string.support_tier_medium),
    Large("support_large", R.string.support_tier_large),
}

/**
 * Google Play Billing for the "Support SceneView" contributions.
 *
 * Every product is a **consumable** one-time product, consumed as soon as Play reports it
 * `PURCHASED`: a contribution is a gift, not an entitlement, so nothing is ever restored and
 * nothing in the app depends on it. A purchase left unconsumed (the app died mid-flow, a
 * pending payment confirmed later) is picked up and consumed on the next [connect].
 *
 * State is Compose state, written on the main thread only ([scope]), so the sheet redraws
 * itself: [availability] gates the amount tiles, [prices] carries Play's localized prices,
 * [outcome] swaps the sheet for its thank-you — only while the sheet is on screen; a result
 * that lands with the sheet closed thanks with a toast instead.
 */
object SupportBilling : PurchasesUpdatedListener {

    enum class Availability { Connecting, Ready, Unavailable }

    enum class Outcome { Thanks, Pending, Failed }

    var availability: Availability by mutableStateOf(Availability.Connecting)
        private set

    /** Localized product details by product id; empty until Play answers. */
    var prices: Map<String, ProductDetails> by mutableStateOf(emptyMap())
        private set

    /** The product whose Play sheet is up, so its tile can show progress. */
    var inFlight: String? by mutableStateOf(null)
        private set

    var outcome: Outcome? by mutableStateOf(null)
        private set

    /** Set by [SupportSheet] while it is composed: where a purchase result can be shown. */
    internal var sheetVisible: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var client: BillingClient? = null
    private var appContext: Context? = null
    private var connectJob: Job? = null

    /**
     * Called each time the sheet or the interstitial opens. A client that is not connected —
     * the first attempt failed, Play was updating, the service dropped — is connected again,
     * with a short bounded backoff; a connected one only refreshes prices and purchases.
     */
    fun connect(context: Context) {
        appContext = context.applicationContext
        val billing = client ?: BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
            .also { client = it }
        if (connectJob?.isActive == true) return
        connectJob = scope.launch {
            if (!billing.isReady) {
                availability = Availability.Connecting
                if (!connectWithBackoff(billing)) {
                    availability = Availability.Unavailable
                    return@launch
                }
            }
            refresh(billing)
        }
    }

    private suspend fun connectWithBackoff(billing: BillingClient): Boolean {
        var wait = FIRST_RETRY_MILLIS
        repeat(CONNECT_ATTEMPTS) { attempt ->
            val result = billing.awaitConnection()
            if (result.responseCode == BillingResponseCode.OK) return true
            Log.i(TAG, "Billing connection ${attempt + 1}/$CONNECT_ATTEMPTS: ${result.responseCode} ${result.debugMessage}")
            if (attempt < CONNECT_ATTEMPTS - 1) {
                delay(wait)
                wait *= 2
            }
        }
        return false
    }

    private suspend fun refresh(billing: BillingClient) {
        val products = SupportTier.entries.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it.productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val details = billing.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(products).build())
        val found = details.productDetailsList.orEmpty().associateBy { it.productId }
        prices = found
        availability = if (details.billingResult.responseCode == BillingResponseCode.OK && found.isNotEmpty()) {
            Availability.Ready
        } else {
            Log.i(TAG, "Support products unavailable: ${details.billingResult.responseCode}, ${found.size} found")
            Availability.Unavailable
        }
        val owned = billing.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        )
        if (owned.billingResult.responseCode == BillingResponseCode.OK) handle(owned.purchasesList, silent = true)
    }

    /** One tap on an amount: Google Play's own purchase sheet, nothing in between. */
    fun buy(activity: Activity, tier: SupportTier) {
        val billing = client ?: return
        val details = prices[tier.productId] ?: return
        outcome = null
        inFlight = tier.productId
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()),
            )
            .build()
        val result = billing.launchBillingFlow(activity, params)
        if (result.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "launchBillingFlow: ${result.responseCode} ${result.debugMessage}")
            inFlight = null
            report(Outcome.Failed)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val list = purchases.orEmpty().toList()
        scope.launch {
            inFlight = null
            when (result.responseCode) {
                BillingResponseCode.OK -> handle(list, silent = false)
                BillingResponseCode.USER_CANCELED -> Unit
                // Play still holds an unconsumed one: consume it, then the next tap works.
                BillingResponseCode.ITEM_ALREADY_OWNED -> client?.let { refresh(it) }
                else -> report(Outcome.Failed)
            }
        }
    }

    private suspend fun handle(purchases: List<Purchase>, silent: Boolean) {
        val ours = purchases.filter { p -> p.products.any { id -> SupportTier.entries.any { it.productId == id } } }
        for (purchase in ours) {
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> consume(purchase, silent)
                Purchase.PurchaseState.PENDING -> if (!silent) report(Outcome.Pending)
                else -> Unit
            }
        }
    }

    private suspend fun consume(purchase: Purchase, silent: Boolean) {
        val billing = client ?: return
        val result = billing.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build())
        if (result.billingResult.responseCode == BillingResponseCode.OK) {
            SupportPrompt.onSupported()
            // A purchase found at start-up (a pending payment Play confirmed while the
            // app was closed) is consumed quietly: no sheet pops up on its own to thank.
            if (!silent) report(Outcome.Thanks)
        } else {
            Log.w(TAG, "consume: ${result.billingResult.responseCode} ${result.billingResult.debugMessage}")
        }
    }

    /**
     * A result reaches the sheet only while it is on screen, so the next opening from About
     * never starts on a stale thank-you; a confirmation that lands with the sheet closed (a
     * pending payment going through) thanks with a toast.
     */
    private fun report(value: Outcome) {
        if (sheetVisible) {
            outcome = value
        } else if (value == Outcome.Thanks) {
            appContext?.let { Toast.makeText(it, R.string.support_thanks_toast, Toast.LENGTH_LONG).show() }
        }
    }

    /** The sheet closed: its next opening starts from the amounts again. */
    fun clearOutcome() {
        outcome = null
    }

    /** Debug QA (`--es support_qa thanks`): the thank-you state, without a purchase. */
    internal fun forceOutcome(value: Outcome) {
        outcome = value
    }

    /**
     * Debug QA (`--es support_qa ready`): sample prices shown as if Play had answered, so the
     * purchase layout can be captured on a build Play has no products for. A tap still goes
     * through [buy], which finds no product and does nothing.
     */
    internal var qaPrices: Map<String, String>? = null

    private const val CONNECT_ATTEMPTS = 3
    private const val FIRST_RETRY_MILLIS = 1_000L
    private const val TAG = "SupportBilling"
}

/** [BillingClient.startConnection] as one suspend call: the setup result, or a disconnect. */
private suspend fun BillingClient.awaitConnection(): BillingResult = suspendCancellableCoroutine { cont ->
    startConnection(object : BillingClientStateListener {
        override fun onBillingSetupFinished(result: BillingResult) {
            // Auto-reconnection may call back again later; only the first answer resumes.
            if (cont.isActive) cont.resume(result)
        }

        override fun onBillingServiceDisconnected() {
            if (cont.isActive) {
                cont.resume(BillingResult.newBuilder().setResponseCode(BillingResponseCode.SERVICE_DISCONNECTED).build())
            }
        }
    })
}
