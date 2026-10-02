import Foundation
import os

/// The collection settings Firebase saved on the device, and why they must go before a
/// `FirebaseApp.configure()` that must not collect. The iOS counterpart of the Android
/// demo's `FirebaseLeftovers` (#4257).
///
/// Builds from before the consent called `setAnalyticsCollectionEnabled(true)`,
/// `setCrashlyticsCollectionEnabled(true)` and `setConsent(analyticsStorage: .granted)`, and
/// this build makes the same calls whenever collection is allowed (outside the zone without
/// an answer, or after "Share"). Both SDKs persist them, and a value set through the API
/// beats the Info.plist NO. Left in place, a start without the consent (push the user
/// already turned on) would collect from the first instant: Analytics would log
/// `app_update` and `session_start`, Crashlytics would upload the reports an older build
/// cached, before `DemoAnalytics.install` turns collection off.
///
/// Cleared, the Info.plist defaults apply (collection off, `analytics_storage` denied) until
/// the consent re-applies its own value. Called by `FirebaseTelemetry.start()` before every
/// configure that must not collect. Key names and locations read from Firebase 12.19
/// (`FIRCLSDataCollectionArbiter`, `FIRCLSUserDefaults`, GoogleAppMeasurement) and checked
/// on a simulator install that had collected.
enum FirebaseLeftovers {
    private static let log = Logger(subsystem: "io.github.sceneview.demo", category: "telemetry")

    /// Crashlytics' own store, a plist under Application Support (not `UserDefaults`).
    static let crashlyticsFile = "com.crashlytics/CLSUserDefaults.plist"
    static let crashlyticsKey = "com.crashlytics.data_collection"

    /// Analytics' consent, in a `UserDefaults` suite of its own.
    static let analyticsSuite = "APMAnalyticsSuiteName"
    static let analyticsConsentKeys = ["consent_settings", "consent_settings_3p", "consent_source"]

    /// Analytics' collection switch, in its own plist under Application Support.
    static let measurementFile = "Google/Measurement/com.google.gmp.measurement.plist"
    static let measurementKey = "/google/measurement/measurement_enabled_state"

    /// Removes the saved collection settings. Never throws; returns how many were found.
    @discardableResult
    static func clear(applicationSupport: URL? = defaultApplicationSupport,
                      bundleIdentifier: String? = Bundle.main.bundleIdentifier,
                      analyticsDefaults: UserDefaults? = UserDefaults(suiteName: analyticsSuite)) -> Int {
        var found = 0
        if let analyticsDefaults {
            for key in analyticsConsentKeys where analyticsDefaults.object(forKey: key) != nil {
                analyticsDefaults.removeObject(forKey: key)
                found += 1
            }
        }
        if let applicationSupport {
            // On macOS, Crashlytics files its store under the bundle identifier.
            var crashlyticsFiles = [applicationSupport.appendingPathComponent(crashlyticsFile)]
            if let bundleIdentifier {
                crashlyticsFiles.append(applicationSupport.appendingPathComponent(bundleIdentifier)
                    .appendingPathComponent(crashlyticsFile))
            }
            for file in crashlyticsFiles {
                found += removeKey(crashlyticsKey, from: file)
            }
            found += removeKey(measurementKey, from: applicationSupport.appendingPathComponent(measurementFile))
        }
        if found > 0 {
            log.notice("cleared \(found, privacy: .public) Firebase collection setting(s) saved earlier")
        }
        return found
    }

    static var defaultApplicationSupport: URL? {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
    }

    /// Rewrites the plist at `url` without `key`, keeping its format. 1 if the key was there.
    private static func removeKey(_ key: String, from url: URL) -> Int {
        guard let data = try? Data(contentsOf: url) else { return 0 }
        var format = PropertyListSerialization.PropertyListFormat.xml
        guard var dictionary = (try? PropertyListSerialization.propertyList(from: data, options: [], format: &format))
                as? [String: Any],
              dictionary.removeValue(forKey: key) != nil,
              let updated = try? PropertyListSerialization.data(fromPropertyList: dictionary, format: format, options: 0)
        else { return 0 }
        do {
            try updated.write(to: url, options: .atomic)
            return 1
        } catch {
            log.error("could not clear a Firebase setting saved earlier: \(error.localizedDescription, privacy: .public)")
            return 0
        }
    }
}
