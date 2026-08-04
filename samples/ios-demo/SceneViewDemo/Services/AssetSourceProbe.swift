import Foundation

/// Where the asset a demo is currently rendering actually came from.
///
/// Mirror of Android's `AssetSourceState` (`DemoScaffold.kt:98`) — keep the
/// three cases and their meaning identical across platforms.
///
///  - ``streamed`` — the model came from the Sketchfab CDN and is now cached on
///    disk. Subsequent launches are instant.
///  - ``streaming`` — a fetch is in flight; nothing has been measured yet.
///  - ``bundled`` — no API key, no network, or a failed download → the offline
///    stand-in declared by `SketchfabSlug.fallbackBundledPath` is on screen.
enum AssetSourceState: Equatable {
    case streamed
    case streaming
    case bundled
}

/// The one rule every iOS demo uses to decide what its asset-source pill says.
///
/// Port of Android's `AssetSourceProbe` (`sketchfab/AssetSourceProbe.kt`, #2989)
/// — same three branches, same precedence, same reasoning. Read that file's
/// KDoc before changing a branch here; the two must not drift.
///
/// The pill must report what was **resolved**, never what was **configured**.
/// "An API key is configured" says nothing about whether the download
/// succeeded: every failure path in `SketchfabAssetResolver.resolve` — no
/// network, aeroplane mode, a stale or 4xx-rejected key, the WAF, a
/// bounds-drifted asset, exhausted retries — ends at `fallbackBundle(for:)`, so
/// a keyed build renders the offline stand-in under whatever the pill claims.
/// Android measured exactly that on the QA emulator (2026-07-28, key
/// configured): all four Multi-Model slots staged out of `fallback/` while the
/// download endpoint answered 429 (#2933).
///
/// Why iOS needs it at all: 14 of the 29 registry slugs fall back to a
/// semantically incompatible bundled USDZ (#2960), and most of those cannot be
/// re-pointed without shipping a new binary. The pill is the systemic
/// mitigation — it makes every remaining substitution *honest* instead of
/// silently confident, which is the #2913 rule ("a confident wrong scene is
/// worse than a visible stall"). Per `feedback_demo_cue_positive_not_absence`
/// it states positively what is on screen rather than flagging an absence.
enum AssetSourceProbe {

    /// The pill for a demo showing a SINGLE streamed slot.
    ///
    /// - Parameters:
    ///   - resolvedURL: the URL the resolver handed back, or `nil` while it is
    ///     still working — the only thing worth measuring.
    ///   - hasAPIKey: whether a Sketchfab key is configured. Read only when
    ///     `loaded` is `false` — NOT when `resolvedURL` is `nil`. The two
    ///     coincide for a demo that calls itself loaded the moment the file
    ///     arrives, but not for one that waits for the model to parse: there, a
    ///     resolved-but-still-parsing streamed file reaches the key branch.
    ///     That is harmless (a resolved *fallback* is claimed by the measured
    ///     branch first, whatever `loaded` says), but do not restate this gate
    ///     as a test on `resolvedURL`.
    ///   - loaded: whether the demo has finished doing what it considers
    ///     "loaded". Each caller owns that definition.
    static func of(
        resolvedURL: URL?,
        hasAPIKey: Bool,
        loaded: Bool
    ) -> AssetSourceState {
        ofAll(resolvedURLs: [resolvedURL], hasAPIKey: hasAPIKey, loaded: loaded)
    }

    /// The pill for a demo showing SEVERAL streamed slots at once.
    ///
    /// A WHOLE-SCENE verdict: one fallen-back slot out of four reads
    /// ``AssetSourceState/bundled`` for all of them, and the pill never says
    /// which slot swapped. Pessimistic on purpose — of the two imprecise
    /// answers, the optimistic one is the one that misleads. It is also a
    /// MOVING verdict during load, since `loaded` and the fallback probe watch
    /// different signals: a slot that falls back last flips the pill
    /// streaming → bundled after the fact.
    ///
    /// - Parameter resolvedURLs: one entry per streamed slot; `nil` for a slot
    ///   still resolving. Slots the demo renders straight from the app bundle
    ///   have no origin question to answer and must not be passed in.
    static func ofAll(
        resolvedURLs: [URL?],
        hasAPIKey: Bool,
        loaded: Bool
    ) -> AssetSourceState {
        // MEASURED. Beats every other branch, including a `loaded` demo with a key.
        if resolvedURLs.contains(where: { url in
            url.map(SketchfabAssetResolver.isBundledFallback) ?? false
        }) {
            return .bundled
        }
        // Nothing to measure yet — the pre-resolve guess, and the only place
        // the key is read.
        guard loaded else { return hasAPIKey ? .streaming : .bundled }
        // Everything resolved, nothing came from `fallback/`: genuinely streamed.
        return .streamed
    }
}
