import Foundation

/// Whether this install must ask before collecting usage statistics: the EEA, the UK and
/// Switzerland (GDPR, UK GDPR, revFADP). The same rules as the Android demo's
/// `ConsentRegion`.
///
/// Decided locally, without any permission and without a network call, from two signals:
/// the region of the user's locale and the time zone identifier. The union is deliberately
/// cautious: the app asks when **any** signal is in the zone, and when **no** signal is
/// readable. Asking someone who did not need to be asked costs one tap; not asking someone
/// who did is a breach.
enum ConsentRegion {
    /// EU 27, then Iceland, Liechtenstein and Norway (EEA), the United Kingdom and
    /// Switzerland. Plus the region codes given to territories that are not countries of
    /// their own, and the grouping codes the locale database can report. Kept identical to
    /// the Android demo's `ConsentRegion` (#4257), plus `IC`, `EA`, `EU` and `150`, which
    /// only Apple's locale database reports.
    static let countries: Set<String> = [
        // EU 27
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE",
        "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
        // EEA outside the EU
        "IS", "LI", "NO",
        // United Kingdom ("UK" is not ISO, but some locales report it) and Switzerland
        "GB", "UK", "CH",
        // Territories with their own code: French overseas regions and Saint-Martin (EU),
        // Åland (Finland), Svalbard (Norway), Gibraltar (UK), Isle of Man, Jersey, Guernsey
        "GF", "GP", "MQ", "RE", "YT", "MF", "AX", "SJ", "GI", "IM", "JE", "GG",
        // Greece's legacy code
        "EL",
        // Apple locale codes: Canary Islands, Ceuta and Melilla, the EU, Europe
        "IC", "EA", "EU", "150",
    ]

    /// Zones outside `Europe/*` that sit in the zone. Same list as Android.
    static let timeZones: Set<String> = [
        "Atlantic/Canary", "Atlantic/Madeira", "Atlantic/Azores", "Atlantic/Reykjavik",
        "Africa/Ceuta", "Indian/Reunion", "Indian/Mayotte", "America/Guadeloupe",
        "America/Martinique", "America/Cayenne", "America/Marigot", "Arctic/Longyearbyen",
        // Cyprus is in the EU but files its zones under Asia/
        "Asia/Nicosia", "Asia/Famagusta",
        // Legacy aliases the tz database still resolves
        "GB", "GB-Eire", "Eire", "Iceland", "Poland", "Portugal", "WET", "CET", "MET", "EET",
    ]

    /// Zones that say nothing about where the device is: no signal at all. Same as Android,
    /// plus `Factory`; every `Etc/*` zone counts too.
    static let placelessTimeZones: Set<String> = [
        "UTC", "UCT", "GMT", "GMT0", "GMT+0", "GMT-0", "Greenwich", "Universal", "Zulu", "Factory",
    ]

    /// The pure decision. `regionCode` is `Locale.region` (`"FR"`), `timeZoneIdentifier`
    /// is `TimeZone.identifier` (`"Europe/Paris"`). Either may be `nil` or meaningless
    /// (`"001"`, `"UTC"`): such a signal counts as unreadable.
    static func requiresConsent(regionCode: String?, timeZoneIdentifier: String?) -> Bool {
        let region = readableRegion(regionCode)
        let zone = readableTimeZone(timeZoneIdentifier)
        guard region != nil || zone != nil else { return true }
        if let region, countries.contains(region) { return true }
        if let zone, zone.hasPrefix("Europe/") || timeZones.contains(zone) { return true }
        return false
    }

    /// This device, now.
    static var current: Bool {
        requiresConsent(regionCode: Locale.current.region?.identifier,
                        timeZoneIdentifier: TimeZone.current.identifier)
    }

    private static func readableRegion(_ code: String?) -> String? {
        guard let code = code?.trimmingCharacters(in: .whitespaces).uppercased(), !code.isEmpty,
              !["001", "ZZ"].contains(code) else { return nil }
        return code
    }

    /// `placelessTimeZones` and `Etc/*` say nothing about where the device is.
    private static func readableTimeZone(_ identifier: String?) -> String? {
        guard let identifier = identifier?.trimmingCharacters(in: .whitespaces), !identifier.isEmpty,
              !placelessTimeZones.contains(identifier),
              !identifier.hasPrefix("Etc/") else { return nil }
        return identifier
    }
}
