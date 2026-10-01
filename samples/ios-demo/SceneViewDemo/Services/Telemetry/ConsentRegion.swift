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
    /// Switzerland. Plus the region codes the locale database gives to EU territories
    /// that are not countries of their own: the French outermost regions, the Canary
    /// Islands (`IC`), Ceuta and Melilla (`EA`), Åland (`AX`), Gibraltar, and the
    /// grouping codes `EU` and `150` (Europe).
    static let countries: Set<String> = [
        // EU 27
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE",
        "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
        // EEA, UK, Switzerland
        "IS", "LI", "NO", "GB", "UK", "CH",
        // EU territories with a region code of their own
        "GP", "MQ", "GF", "RE", "YT", "MF", "IC", "EA", "AX", "GI",
        // Groupings
        "EU", "150",
    ]

    /// Zones outside `Europe/*` that sit in the zone: the Canaries, Madeira, the Azores,
    /// Iceland, Ceuta, the French overseas regions, Svalbard.
    static let timeZones: Set<String> = [
        "Atlantic/Canary", "Atlantic/Madeira", "Atlantic/Azores", "Atlantic/Reykjavik",
        "Africa/Ceuta", "Indian/Reunion", "Indian/Mayotte", "America/Guadeloupe",
        "America/Martinique", "America/Cayenne", "Arctic/Longyearbyen",
        "Europe/Mariehamn", "Europe/Gibraltar",
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

    /// `UTC`, `GMT`, `Etc/*` and `Factory` say nothing about where the device is.
    private static func readableTimeZone(_ identifier: String?) -> String? {
        guard let identifier = identifier?.trimmingCharacters(in: .whitespaces), !identifier.isEmpty,
              !["UTC", "GMT", "Factory", "Universal", "Zulu"].contains(identifier),
              !identifier.hasPrefix("Etc/") else { return nil }
        return identifier
    }
}
