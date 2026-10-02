package io.github.sceneview.demo.telemetry

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The collection flags Firebase saved must be gone before a start that must not collect, whatever
 * the consent version: an install that collected outside the zone, then moved inside it with push
 * on, would otherwise start Firebase collecting before the consent sheet.
 */
@RunWith(RobolectricTestRunner::class)
class FirebaseLeftoversTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val measurement: SharedPreferences =
        context.getSharedPreferences("com.google.android.gms.measurement.prefs", Context.MODE_PRIVATE)
    private val crashlytics: SharedPreferences =
        context.getSharedPreferences("com.google.firebase.crashlytics", Context.MODE_PRIVATE)

    @Before
    fun seedWhatACollectingInstallSaved() {
        // What Analytics and Crashlytics persist after setCollectionEnabled(true) outside the zone.
        measurement.edit()
            .clear()
            .putBoolean("measurement_enabled", true)
            .putBoolean("measurement_enabled_from_api", true)
            .putString("consent_settings", "G1--")
            .putInt("consent_source", 30)
            .putString("unrelated_key", "kept")
            .commit()
        crashlytics.edit().clear().putBoolean("firebase_crashlytics_collection_enabled", true).commit()
        // Already on the current consent version: the migration has nothing to do.
        TelemetryPrefs(context).consentVersion = TelemetryConsent.VERSION
    }

    @Test
    fun `a start without consent clears the saved flags before Firebase initializes`() {
        var flagsAtInit: Map<String, *>? = null
        var crashlyticsAtInit: Boolean? = null

        val result = Telemetry.initializeFirebase(context, collecting = false) {
            flagsAtInit = measurement.all.toMap()
            crashlyticsAtInit = crashlytics.contains("firebase_crashlytics_collection_enabled")
            "started"
        }

        assertEquals("started", result)
        val seen = requireNotNull(flagsAtInit)
        listOf("measurement_enabled", "measurement_enabled_from_api", "consent_settings", "consent_source")
            .forEach { assertFalse("$it still saved when Firebase initializes", seen.containsKey(it)) }
        assertEquals(false, crashlyticsAtInit)
        // Only Firebase's collection flags go, nothing else in its storage.
        assertEquals("kept", seen["unrelated_key"])
    }

    @Test
    fun `a collecting start keeps the saved flags`() {
        Telemetry.initializeFirebase(context, collecting = true) { Unit }

        assertTrue(measurement.getBoolean("measurement_enabled", false))
        assertTrue(crashlytics.getBoolean("firebase_crashlytics_collection_enabled", false))
    }

    @Test
    fun `clear removes exactly the collection flags and is a no-op once they are gone`() {
        FirebaseLeftovers.clear(context)
        FirebaseLeftovers.clear(context)

        assertFalse(measurement.contains("measurement_enabled"))
        assertFalse(measurement.contains("dma_consent_settings"))
        assertFalse(crashlytics.contains("firebase_crashlytics_collection_enabled"))
        assertEquals("kept", measurement.getString("unrelated_key", null))
    }
}
