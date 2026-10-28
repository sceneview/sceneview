package io.github.sceneview.demo.support

import android.app.Activity
import android.content.Context
import android.util.Log
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
import io.github.sceneview.demo.R

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
 * State is Compose state so the sheet redraws itself: [availability] gates the amount tiles,
 * [prices] carries Play's localized prices, [outcome] swaps the sheet for its thank-you.
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

    private var client: BillingClient? = null

    /** Idempotent: connects once per process, then refreshes prices and pending purchases. */
    fun connect(context: Context) {
        val existing = client
        if (existing != null) {
            if (existing.isReady) refresh(existing)
            return
        }
        val billing = BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        client = billing
        availability = Availability.Connecting
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingResponseCode.OK) {
                    refresh(billing)
                } else {
                    Log.i(TAG, "Billing unavailable: ${result.responseCode} ${result.debugMessage}")
                    availability = Availability.Unavailable
                }
            }

            override fun onBillingServiceDisconnected() {
                // enableAutoServiceReconnection() reconnects on the next call.
            }
        })
    }

    private fun refresh(billing: BillingClient) {
        val products = SupportTier.entries.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it.productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        billing.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(products).build(),
        ) { result, details ->
            val found = details.productDetailsList.associateBy { it.productId }
            prices = found
            availability = if (result.responseCode == BillingResponseCode.OK && found.isNotEmpty()) {
                Availability.Ready
            } else {
                Log.i(TAG, "Support products unavailable: ${result.responseCode}, ${found.size} found")
                Availability.Unavailable
            }
        }
        billing.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        ) { result, purchases ->
            if (result.responseCode == BillingResponseCode.OK) handle(purchases, silent = true)
        }
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
            outcome = Outcome.Failed
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        inFlight = null
        when (result.responseCode) {
            BillingResponseCode.OK -> handle(purchases.orEmpty(), silent = false)
            BillingResponseCode.USER_CANCELED -> Unit
            // Play still holds an unconsumed one: consume it, then the next tap works.
            BillingResponseCode.ITEM_ALREADY_OWNED -> client?.let { refresh(it) }
            else -> outcome = Outcome.Failed
        }
    }

    private fun handle(purchases: List<Purchase>, silent: Boolean) {
        val ours = purchases.filter { p -> p.products.any { id -> SupportTier.entries.any { it.productId == id } } }
        for (purchase in ours) {
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> consume(purchase, silent)
                Purchase.PurchaseState.PENDING -> if (!silent) outcome = Outcome.Pending
                else -> Unit
            }
        }
    }

    private fun consume(purchase: Purchase, silent: Boolean) {
        val billing = client ?: return
        billing.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { result, _ ->
            if (result.responseCode == BillingResponseCode.OK) {
                SupportPrompt.onSupported()
                // A purchase found at start-up (a pending payment Play confirmed while the
                // app was closed) is consumed quietly: no sheet pops up on its own to thank.
                if (!silent) outcome = Outcome.Thanks
            } else {
                Log.w(TAG, "consume: ${result.responseCode} ${result.debugMessage}")
            }
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

    private const val TAG = "SupportBilling"
}
