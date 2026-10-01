package io.github.sceneview.demo.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who is asked before anything is collected: the EEA, the UK and Switzerland, by any signal. */
class ConsentRegionTest {

    private fun signals(
        network: String? = null,
        sim: String? = null,
        locale: String? = null,
        zone: String? = null,
    ) = ConsentSignals(networkCountry = network, simCountry = sim, localeRegion = locale, timeZoneId = zone)

    @Test
    fun `a French locale is enough even with a US time zone`() {
        assertTrue(ConsentRegion.requiresConsent(signals(locale = "FR", zone = "America/New_York")))
    }

    @Test
    fun `a US device on every signal is not asked`() {
        assertFalse(
            ConsentRegion.requiresConsent(
                signals(network = "us", sim = "us", locale = "US", zone = "America/New_York"),
            ),
        )
    }

    @Test
    fun `no readable signal is asked`() {
        assertTrue(ConsentRegion.requiresConsent(signals()))
        assertTrue(ConsentRegion.requiresConsent(signals(network = "", sim = " ", locale = "", zone = "")))
    }

    @Test
    fun `the Canaries are in the zone by their time zone`() {
        assertTrue(ConsentRegion.requiresConsent(signals(locale = "US", zone = "Atlantic/Canary")))
    }

    @Test
    fun `the UK and Switzerland are in the zone`() {
        assertTrue(ConsentRegion.requiresConsent(signals(network = "gb", locale = "US", zone = "America/New_York")))
        assertTrue(ConsentRegion.requiresConsent(signals(sim = "ch", locale = "US", zone = "America/New_York")))
        assertTrue(ConsentRegion.requiresConsent(signals(locale = "US", zone = "Europe/London")))
        assertTrue(ConsentRegion.requiresConsent(signals(locale = "US", zone = "Europe/Zurich")))
    }

    @Test
    fun `the EEA outside the EU is in the zone`() {
        listOf("IS", "LI", "NO").forEach { country ->
            assertTrue(country, ConsentRegion.requiresConsent(signals(sim = country, zone = "America/New_York")))
        }
    }

    @Test
    fun `a SIM from the zone counts while roaming abroad`() {
        assertTrue(
            ConsentRegion.requiresConsent(signals(network = "jp", sim = "de", locale = "JP", zone = "Asia/Tokyo")),
        )
    }

    @Test
    fun `French overseas regions are in the zone`() {
        assertTrue(ConsentRegion.requiresConsent(signals(locale = "US", zone = "Indian/Reunion")))
        assertTrue(ConsentRegion.requiresConsent(signals(network = "gp", locale = "US", zone = "America/New_York")))
    }

    @Test
    fun `a time zone alone outside the zone is not asked`() {
        assertFalse(ConsentRegion.requiresConsent(signals(zone = "America/Los_Angeles")))
        assertFalse(ConsentRegion.requiresConsent(signals(zone = "Asia/Tokyo")))
    }

    @Test
    fun `country codes are case-insensitive`() {
        assertTrue(ConsentRegion.requiresConsent(signals(locale = "fr")))
        assertTrue(ConsentRegion.requiresConsent(signals(network = " Fr ")))
    }

    @Test
    fun `Cyprus is in the zone by its Asia time zones`() {
        assertTrue(ConsentRegion.requiresConsent(signals(locale = "US", zone = "Asia/Nicosia")))
        assertTrue(ConsentRegion.requiresConsent(signals(locale = "US", zone = "Asia/Famagusta")))
    }

    @Test
    fun `the Crown Dependencies are in the zone`() {
        listOf("IM", "JE", "GG").forEach { country ->
            assertTrue(country, ConsentRegion.requiresConsent(signals(sim = country, zone = "America/New_York")))
        }
    }

    @Test
    fun `a placeless time zone is no signal and fails closed`() {
        listOf("UTC", "GMT", "Etc/UTC", "Etc/GMT+3", "Zulu").forEach { zone ->
            assertTrue(zone, ConsentRegion.requiresConsent(signals(zone = zone)))
        }
    }

    @Test
    fun `a placeless time zone does not outweigh a country outside the zone`() {
        assertFalse(ConsentRegion.requiresConsent(signals(locale = "US", zone = "Etc/UTC")))
        assertFalse(ConsentRegion.requiresConsent(signals(network = "us", zone = "GMT")))
    }
}
