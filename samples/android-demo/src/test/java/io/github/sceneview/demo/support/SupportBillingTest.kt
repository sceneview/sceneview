package io.github.sceneview.demo.support

import android.app.Activity
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import io.github.sceneview.demo.telemetry.AnalyticsEvent
import io.github.sceneview.demo.telemetry.SupportPromptSource
import io.github.sceneview.demo.telemetry.SupportResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

/** Real billing callbacks and consumption, with only the Play service replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34], shadows = [ShadowSupportBillingClient::class],
)
class SupportBillingTest {
    @Before
    fun connect() {
        resetSupportBillingForTest()
        SupportBilling.sheetVisible = true
        SupportBilling.connect(ApplicationProvider.getApplicationContext())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SupportBilling.Availability.Ready, SupportBilling.availability)
    }

    @After
    fun clear() {
        SupportBilling.sheetVisible = false
        SupportBilling.clearOutcome()
    }

    @Test fun alreadyOwnedAtLaunchShowsThanks() = recoverOwned(atLaunch = true, pending = false)

    @Test fun alreadyOwnedInCallbackShowsThanks() = recoverOwned(atLaunch = false, pending = false)

    @Test fun alreadyOwnedAtLaunchShowsPending() = recoverOwned(atLaunch = true, pending = true)

    @Test fun alreadyOwnedInCallbackShowsPending() = recoverOwned(atLaunch = false, pending = true)

    private fun recoverOwned(atLaunch: Boolean, pending: Boolean) {
        ShadowSupportBillingClient.owned = listOf(purchase(pending))
        ShadowSupportBillingClient.launchCode = if (atLaunch) BillingResponseCode.ITEM_ALREADY_OWNED else BillingResponseCode.OK
        val events = mutableListOf<AnalyticsEvent>()
        val session = SupportPromptAnalyticsSession(SupportPromptSource.Home, events::add)
        session.shown()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            SupportBilling.buy(activity.get(), SupportTier.Tiny, session)
            if (!atLaunch) SupportBilling.onPurchasesUpdated(billingResult(BillingResponseCode.ITEM_ALREADY_OWNED), null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                if (pending) SupportBilling.Outcome.Pending else SupportBilling.Outcome.Thanks,
                SupportBilling.outcome,
            )
            assertEquals(null, SupportBilling.inFlight)
            assertEquals(if (pending) 0 else 1, ShadowSupportBillingClient.consumptions)
            session.close()
            // The sheet opened only when Play answered OK at launch; either way, nothing about the
            // payment reaches analytics.
            assertEquals(
                AnalyticsEvent.SupportPromptResult(
                    if (atLaunch) SupportResult.Dismissed else SupportResult.Store,
                    SupportPromptSource.Home,
                ),
                events.last(),
            )
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun consumeFailureReportsFailedAndTheOwnedPurchaseCanBeRecovered() {
        ShadowSupportBillingClient.consumeCode = BillingResponseCode.ERROR
        val paid = purchase()
        SupportBilling.onPurchasesUpdated(billingResult(BillingResponseCode.OK), mutableListOf(paid))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SupportBilling.Outcome.Failed, SupportBilling.outcome)
        assertEquals(1, ShadowSupportBillingClient.consumptions)

        ShadowSupportBillingClient.consumeCode = BillingResponseCode.OK
        ShadowSupportBillingClient.owned = listOf(paid)
        SupportBilling.onPurchasesUpdated(billingResult(BillingResponseCode.ITEM_ALREADY_OWNED), null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SupportBilling.Outcome.Thanks, SupportBilling.outcome)
    }

    @Test
    fun thanksWinsOverLaterCallbacksUntilCleared() {
        SupportBilling.onPurchasesUpdated(billingResult(BillingResponseCode.OK), mutableListOf(purchase()))
        shadowOf(Looper.getMainLooper()).idle()
        SupportBilling.onPurchasesUpdated(billingResult(BillingResponseCode.ERROR), null)
        SupportBilling.onPurchasesUpdated(billingResult(BillingResponseCode.OK), mutableListOf(purchase(pending = true)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SupportBilling.Outcome.Thanks, SupportBilling.outcome)
        SupportBilling.clearOutcome()
        assertEquals(null, SupportBilling.outcome)
    }

    private fun purchase(pending: Boolean = false) = Purchase(
        """{"productId":"support_tiny","purchaseToken":"test-token","purchaseState":${if (pending) 4 else 0}}""",
        "test-signature",
    )
}

internal fun billingResult(code: Int): BillingResult = BillingResult.newBuilder().setResponseCode(code).build()

internal fun resetSupportBillingForTest() {
    ShadowSupportBillingClient.owned = emptyList()
    ShadowSupportBillingClient.launchCode = BillingResponseCode.OK
    ShadowSupportBillingClient.consumeCode = BillingResponseCode.OK
    ShadowSupportBillingClient.consumptions = 0
    ReflectionHelpers.setField(
        SupportBilling, "client", Shadow.newInstanceOf(Class.forName("com.android.billingclient.api.BillingClientImpl")),
    )
    ReflectionHelpers.getField<kotlinx.coroutines.Job?>(SupportBilling, "connectJob")?.cancel()
    ReflectionHelpers.setField(SupportBilling, "connectJob", null)
    ReflectionHelpers.getField<androidx.compose.runtime.MutableState<String?>>(SupportBilling, "inFlight\$delegate").value = null
    SupportBilling.qaPrices = null
    SupportBilling.qaUnavailable = false
    SupportBilling.sheetVisible = false
    SupportBilling.clearOutcome()
    SupportPrompt.resetForTest(ApplicationProvider.getApplicationContext())
}

/** Play's boundary only: the production recovery and outcome code still runs in tests. */
// Shadow methods retain Play's signatures; unused inputs and readiness are intentional.
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
@Implements(className = "com.android.billingclient.api.BillingClientImpl", isInAndroidSdk = false)
class ShadowSupportBillingClient {
    @Implementation fun isReady() = true

    @Implementation
    fun startConnection(listener: BillingClientStateListener) {
        listener.onBillingSetupFinished(billingResult(BillingResponseCode.OK))
    }

    @Implementation
    fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult = billingResult(launchCode)

    @Implementation
    fun queryProductDetailsAsync(params: QueryProductDetailsParams, listener: ProductDetailsResponseListener) {
        val product = ProductDetails::class.java.getDeclaredConstructor(String::class.java).apply { isAccessible = true }
            .newInstance(
                """{"productId":"support_tiny","type":"inapp","title":"Small thanks","name":"Small thanks",
                    "description":"Support SceneView","oneTimePurchaseOfferDetails":{
                    "formattedPrice":"$0.99","priceAmountMicros":990000,"priceCurrencyCode":"USD"}}""",
            )
        listener.onProductDetailsResponse(
            billingResult(BillingResponseCode.OK), QueryProductDetailsResult.create(listOf(product), emptyList()),
        )
    }

    @Implementation
    fun queryPurchasesAsync(params: QueryPurchasesParams, listener: PurchasesResponseListener) {
        listener.onQueryPurchasesResponse(billingResult(BillingResponseCode.OK), owned)
    }

    @Implementation
    fun consumeAsync(params: ConsumeParams, listener: ConsumeResponseListener) {
        consumptions++
        listener.onConsumeResponse(billingResult(consumeCode), params.purchaseToken)
    }

    companion object {
        var owned: List<Purchase> = emptyList()
        var launchCode = BillingResponseCode.OK
        var consumeCode = BillingResponseCode.OK
        var consumptions = 0
    }
}
