package io.github.sceneview.demo.telemetry

import android.content.Context
import android.telephony.TelephonyManager
import java.util.TimeZone

/**
 * What the device says about where it is, read locally and without any permission. Every field
 * is nullable: a tablet has no SIM, an emulator may have no network operator, and a blank value
 * counts as absent.
 */
data class ConsentSignals(
    /** `TelephonyManager.networkCountryIso` — the country of the network the device is on. */
    val networkCountry: String?,
    /** `TelephonyManager.simCountryIso` — the country that issued the SIM. */
    val simCountry: String?,
    /** The region of the app's primary locale (`fr-FR` → `FR`). */
    val localeRegion: String?,
    /** `TimeZone.getDefault().id`, e.g. `Europe/Paris`. */
    val timeZoneId: String?,
) {
    companion object {
        /** Reads the four signals. Never throws: a signal that cannot be read is null. */
        fun read(context: Context): ConsentSignals {
            val telephony = runCatching {
                context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            }.getOrNull()
            return ConsentSignals(
                networkCountry = runCatching { telephony?.networkCountryIso }.getOrNull(),
                simCountry = runCatching { telephony?.simCountryIso }.getOrNull(),
                localeRegion = runCatching {
                    context.resources.configuration.locales[0]?.country
                }.getOrNull(),
                timeZoneId = runCatching { TimeZone.getDefault().id }.getOrNull(),
            )
        }
    }
}

/**
 * Whether this install must be asked before any usage statistics or crash report is collected:
 * the EEA (EU 27, Iceland, Liechtenstein, Norway), the United Kingdom and Switzerland. Pure logic,
 * no Android, unit-tested.
 *
 * The answer is the cautious **union** of the signals: one signal in the zone is enough, and a
 * device where no signal can be read is asked too. Asking someone outside the zone costs one
 * sheet; not asking someone inside it is the failure that matters. So the lists below lean wide:
 * every `Europe/` time zone counts (Moscow and Istanbul included), and the EU's outermost regions
 * and the territories that carry their own ISO code count as their country does.
 */
object ConsentRegion {

    /** ISO 3166-1 alpha-2 codes, upper case. */
    internal val COUNTRIES: Set<String> = setOf(
        // EU 27
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT",
        "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
        // EEA outside the EU
        "IS", "LI", "NO",
        // United Kingdom ("UK" is not ISO, but some carriers and locales report it) and Switzerland
        "GB", "UK", "CH",
        // Territories with their own code: French overseas regions and Saint-Martin (EU),
        // Åland (Finland), Svalbard (Norway), Gibraltar (UK), and the Crown Dependencies that
        // carriers and locales report on their own: Isle of Man, Jersey, Guernsey
        "GF", "GP", "MQ", "RE", "YT", "MF", "AX", "SJ", "GI", "IM", "JE", "GG",
        // Region codes the locale data gives EU territories: Canary Islands, Ceuta and Melilla
        "IC", "EA",
        // Greece's legacy code, still reported by some carriers
        "EL",
        // Groupings a locale can carry: European Union, Europe (UN M49)
        "EU", "150",
    )

    /** Region codes that name no place (World, Unknown): treated as no signal. */
    internal val PLACELESS_REGIONS: Set<String> = setOf("001", "ZZ")

    /** Zones outside `Europe/` that belong to the zone. */
    internal val TIME_ZONES: Set<String> = setOf(
        "Atlantic/Canary", "Atlantic/Madeira", "Atlantic/Azores", "Atlantic/Reykjavik",
        "Africa/Ceuta", "Indian/Reunion", "Indian/Mayotte", "America/Guadeloupe",
        "America/Martinique", "America/Cayenne", "America/Marigot", "Arctic/Longyearbyen",
        // Cyprus is in the EU but files its zones under Asia/
        "Asia/Nicosia", "Asia/Famagusta",
        // Legacy aliases the tz database still resolves
        "GB", "GB-Eire", "Eire", "Iceland", "Poland", "Portugal", "WET", "CET", "MET", "EET",
    )

    private const val EUROPE_PREFIX = "Europe/"

    /**
     * Zones that say nothing about where the device is (a server-style default, or a device set
     * to UTC on purpose). Treated as no signal at all, so a device with only one of these and no
     * readable country is asked.
     */
    internal val PLACELESS_TIME_ZONES: Set<String> = setOf(
        "UTC", "UCT", "GMT", "GMT0", "GMT+0", "GMT-0", "Greenwich", "Universal", "Zulu", "Factory",
    )
    private const val ETC_PREFIX = "Etc/"

    /** True when the consent sheet must be shown before anything is collected. */
    fun requiresConsent(signals: ConsentSignals): Boolean {
        val countries = listOf(signals.networkCountry, signals.simCountry, signals.localeRegion)
            .mapNotNull { it?.trim()?.uppercase() }
            .filter { it.isNotEmpty() && it !in PLACELESS_REGIONS }
        val zone = signals.timeZoneId?.trim()?.takeIf { it.isNotEmpty() && !isPlaceless(it) }
        if (countries.isEmpty() && zone == null) return true
        return countries.any { it in COUNTRIES } || (zone != null && isInZone(zone))
    }

    private fun isInZone(zone: String): Boolean = zone.startsWith(EUROPE_PREFIX) || zone in TIME_ZONES

    private fun isPlaceless(zone: String): Boolean = zone.startsWith(ETC_PREFIX) || zone in PLACELESS_TIME_ZONES
}
