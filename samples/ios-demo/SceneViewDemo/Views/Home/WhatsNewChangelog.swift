import Foundation

// The "What's new" sheet's content — a port of Android's `WhatsNewChangelog.kt`
// (samples/android-demo/.../whatsnew). Same source: the repo-root `CHANGELOG.md`,
// copied into the app bundle by the "Bundle changelog" build phase, so the sheet
// is derived and nothing on it is hand-maintained. Same parsing rules, so both
// apps read the same release notes the same way; only the scope filter differs —
// each app keeps the entries written about itself (``WhatsNewChangelog/demoAppHeadline(_:)``).

enum WhatsNewCategory: CaseIterable, Sendable {
    case added, fixed, changed, performance, removed

    /// Android's `whats_new_category_*` strings.
    var label: String {
        switch self {
        case .added: return "Added"
        case .fixed: return "Fixed"
        case .changed: return "Changed"
        case .performance: return "Performance"
        case .removed: return "Removed"
        }
    }
}

struct WhatsNewHighlight: Hashable, Sendable {
    let category: WhatsNewCategory
    let headline: String
    let issueNumber: Int?
}

struct WhatsNewRelease: Hashable, Sendable, Identifiable {
    let version: String
    let date: String?
    let title: String?
    let highlights: [WhatsNewHighlight]

    var id: String { version }
}

struct WhatsNewSection: Hashable, Sendable {
    /// `nil` for the `## Unreleased` block.
    let version: String?
    let date: String?
    let title: String?
    var entries: [WhatsNewHighlight]

    var isUnreleased: Bool { version == nil }
}

enum WhatsNewChangelog {
    /// Bundled file name — Android's `WHATS_NEW_ASSET`.
    static let resourceName = "CHANGELOG"
    /// Releases the sheet lists — Android's `WHATS_NEW_MAX_RELEASES`.
    static let maxReleases = 3
    /// How far back the sheet reads before scoping to the demo app — Android's
    /// `WHATS_NEW_MAX_SINCE_RELEASES`. A release that only touched the SDK,
    /// Android or the website has nothing left once scoped.
    static let maxSinceReleases = 20

    /// The sheet's releases, newest first: the bundled changelog, scoped to the
    /// iOS demo app, empty releases dropped, `maxReleases` kept. Empty when the
    /// file is missing — the sheet's entry then stays hidden.
    static func load(bundle: Bundle = .main, maxReleases: Int = maxReleases) -> [WhatsNewRelease] {
        guard let url = bundle.url(forResource: resourceName, withExtension: "md"),
              let markdown = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return releases(fromChangelog: markdown, maxReleases: maxReleases)
    }

    /// `load` without the bundle, for tests.
    static func releases(fromChangelog markdown: String, maxReleases: Int = maxReleases) -> [WhatsNewRelease] {
        let sections = forDemoApp(parseSections(markdown, maxReleases: maxSinceReleases))
            .filter { !$0.entries.isEmpty }
        return toReleases(sections, maxReleases: maxReleases)
    }

    /// Every release, unscoped — Android's `parseWhatsNew`.
    static func parse(_ markdown: String, maxReleases: Int = maxReleases) -> [WhatsNewRelease] {
        toReleases(parseSections(markdown, maxReleases: maxReleases), maxReleases: maxReleases)
    }

    private static func toReleases(_ sections: [WhatsNewSection], maxReleases: Int) -> [WhatsNewRelease] {
        sections.filter { !$0.isUnreleased }
            .prefix(maxReleases)
            .map { section in
                WhatsNewRelease(
                    version: section.version ?? "",
                    date: section.date,
                    title: section.title,
                    // Code spans stay in the source; the sheet shows them plain.
                    highlights: section.entries.map {
                        WhatsNewHighlight(category: $0.category,
                                          headline: $0.headline.replacingOccurrences(of: "`", with: ""),
                                          issueNumber: $0.issueNumber)
                    }
                )
            }
    }

    // MARK: - Sections

    /// Android's `parseChangelogSections`: `## vX — date — title` releases (and
    /// the `## Unreleased` block), `### Added|Fixed|Changed|Performance|Removed`
    /// groups, one entry per top-level bullet. Tests / Docs are not user-facing.
    static func parseSections(_ markdown: String, maxReleases: Int = maxReleases) -> [WhatsNewSection] {
        var sections: [WhatsNewSection] = []
        var releaseCount = 0

        var open = false
        var version: String?
        var date: String?
        var title: String?
        var entries: [WhatsNewHighlight] = []
        var category: WhatsNewCategory?

        func flush() {
            // An empty `## Unreleased` is the committed placeholder; a released
            // section with no user-facing bullets is still a release.
            if open && !(version == nil && entries.isEmpty) {
                sections.append(WhatsNewSection(version: version, date: date, title: title, entries: entries))
                if version != nil { releaseCount += 1 }
            }
            open = false
            version = nil
            date = nil
            title = nil
            entries = []
            category = nil
        }

        for line in markdown.split(separator: "\n", omittingEmptySubsequences: false).map(String.init) {
            if line.hasPrefix("## ") {
                flush()
                if releaseCount >= maxReleases { break }
                if let header = parseReleaseHeader(line) {
                    open = true
                    version = header.version
                    date = header.date
                    title = header.title
                } else if line.dropFirst(3).trimmingCharacters(in: .whitespaces).lowercased() == "unreleased" {
                    open = true // version stays nil — the unreleased block.
                }
                continue
            }
            guard open else { continue }
            if line.hasPrefix("### ") {
                switch line.dropFirst(4).trimmingCharacters(in: .whitespaces).lowercased() {
                case "added": category = .added
                case "fixed": category = .fixed
                case "changed": category = .changed
                case "performance": category = .performance
                case "removed": category = .removed
                default: category = nil
                }
            } else if line.hasPrefix("- "), let category, let entry = parseEntry(line, category: category) {
                // Top-level bullets only: continuation lines are indented.
                entries.append(entry)
            }
        }
        flush()
        return sections
    }

    private struct ReleaseHeader {
        let version: String
        let date: String?
        let title: String?
    }

    private static func parseReleaseHeader(_ line: String) -> ReleaseHeader? {
        // Parts are separated by em dashes: `## v4.30.0 — 2026-08-12 — Title`.
        let parts = line.dropFirst(3).trimmingCharacters(in: .whitespaces)
            .components(separatedBy: " — ")
            .map { $0.trimmingCharacters(in: .whitespaces) }
        guard let first = parts.first else { return nil }
        let version = first.hasPrefix("v") ? String(first.dropFirst()) : first
        guard Pattern.version.matchesEntirely(version) else { return nil }

        var date: String?
        var titleParts: [String] = []
        for part in parts.dropFirst() {
            if Pattern.isoDate.matchesEntirely(part) {
                date = part
            } else if let groups = Pattern.titleWithTrailingDate.entireMatchGroups(part) {
                // Pre-4.24 form: `Title (2026-07-18)`.
                titleParts.append(groups[0])
                date = groups[1]
            } else if !part.isEmpty {
                titleParts.append(part)
            }
        }
        let title = titleParts.joined(separator: " — ")
        return ReleaseHeader(version: version, date: date, title: title.isEmpty ? nil : title)
    }

    // MARK: - Entries

    private static func parseEntry(_ line: String, category: WhatsNewCategory) -> WhatsNewHighlight? {
        let raw = line.dropFirst(2).trimmingCharacters(in: .whitespaces)
        guard !raw.isEmpty else { return nil }
        let issueNumber = Pattern.issueRef.firstGroup(raw).flatMap(Int.init)

        // A bold lead is the headline; `**scope**: prose` (or `**scope:** prose`)
        // is a prefix, and the sentence after it is the headline — Android's
        // `parseEntry`, same reasons.
        let leading: String
        if raw.hasPrefix("**") {
            let boldStart = raw.index(raw.startIndex, offsetBy: 2)
            guard let close = raw.range(of: "**", range: boldStart..<raw.endIndex),
                  close.lowerBound > boldStart else {
                return finish(raw, category: category, issueNumber: issueNumber)
            }
            let bold = String(raw[boldStart..<close.lowerBound])
            let after = raw[close.upperBound...]
            if after.first == ":" {
                leading = bold + ": " + firstSentence(after.dropFirst().trimmingCharacters(in: .whitespaces))
            } else if bold.hasSuffix(":") {
                leading = String(bold.dropLast()) + ": " + firstSentence(after.trimmingCharacters(in: .whitespaces))
            } else {
                leading = bold
            }
        } else {
            leading = firstSentence(raw)
        }
        return finish(leading, category: category, issueNumber: issueNumber)
    }

    /// Links, bold and emphasis stripped, trailing `.` / `:` trimmed.
    private static func finish(_ leading: String, category: WhatsNewCategory, issueNumber: Int?) -> WhatsNewHighlight? {
        var text = Pattern.issueLinkGroup.replace(leading, with: "")
        text = Pattern.markdownLink.replace(text, with: "$1")
        text = text.replacingOccurrences(of: "**", with: "")
        text = Pattern.emphasis.replace(text, with: "$1")
        text = text.trimmingCharacters(in: .whitespaces)
        while let last = text.last, last == "." || last == ":" { text.removeLast() }
        text = text.trimmingCharacters(in: .whitespaces)
        return text.isEmpty ? nil : WhatsNewHighlight(category: category, headline: text, issueNumber: issueNumber)
    }

    private static func firstSentence(_ text: String) -> String {
        guard let end = text.range(of: ". "), end.lowerBound > text.startIndex else { return text }
        return String(text[..<text.index(after: end.lowerBound)])
    }

    // MARK: - Demo-app scope

    /// The headline as the iOS demo app shows it, or `nil` when the entry is not
    /// about the iOS demo app — Android's `demoAppHeadline`, scoped to this app.
    ///
    /// Android keeps `Android demo`, `Demo apps`, `Demo (Android)` and a bare
    /// `Demo —`; here, `iOS demo` (`iOS demo app`, `iOS / macOS demo`, `The iOS
    /// demo's …`), `Demo apps` (both apps) and `Demo (iOS)`. A bare "demo app" is
    /// Android's in this changelog, so it stays out. A scope that is only a label
    /// (`iOS demo: `, `iOS demo — `) is stripped and the rest capitalised; a scope
    /// that is the sentence's subject (`The iOS demo's About tab …`) is kept.
    static func demoAppHeadline(_ headline: String) -> String? {
        guard let scope = Pattern.demoAppScope.firstMatchRange(headline) else { return nil }
        let rest = String(headline[scope.upperBound...])
        guard let separator = Pattern.scopeLabelSeparator.firstMatchRange(rest) else { return headline }
        let stripped = rest[separator.upperBound...].trimmingCharacters(in: .whitespaces)
        guard let first = stripped.first else { return nil }
        return first.uppercased() + stripped.dropFirst()
    }

    static func forDemoApp(_ sections: [WhatsNewSection]) -> [WhatsNewSection] {
        sections.map { section in
            var scoped = section
            scoped.entries = section.entries.compactMap { entry in
                demoAppHeadline(entry.headline).map {
                    WhatsNewHighlight(category: entry.category, headline: $0, issueNumber: entry.issueNumber)
                }
            }
            return scoped
        }
    }
}

// MARK: - Patterns

/// ICU patterns, verbatim from Android where they exist (Swift `Regex` has no
/// look-behind, which `emphasis` needs).
private enum Pattern {
    static let version = ICU(#"\d+(\.\d+)*"#)
    static let isoDate = ICU(#"\d{4}-\d{2}-\d{2}"#)
    static let titleWithTrailingDate = ICU(#"^(.*\S)\s*\((\d{4}-\d{2}-\d{2})\)$"#)
    static let issueRef = ICU(#"\[#(\d+)]"#)
    /// ` ([#123](…), [#124](…))` — the issue links after a headline.
    static let issueLinkGroup = ICU(#"\s*\((?:\[#\d+]\([^)]*\)(?:,\s*)?)+\)"#)
    static let markdownLink = ICU(#"\[([^\]]*)]\([^)]*\)"#)
    /// `*emphasis*` — single asterisks around non-blank text.
    static let emphasis = ICU(#"\*(?=\S)([^*]+?)(?<=\S)\*"#)
    static let demoAppScope = ICU(
        #"^(?:the\s+)?(?:ios(?:\s*/\s*macos)?\s+demo(?:\s+app)?|demo\s+apps|demo\s+\(ios\))(?![\w(])"#,
        caseInsensitive: true
    )
    static let scopeLabelSeparator = ICU(#"^\s*[:,—]\s*"#)
}

/// A compiled `NSRegularExpression` with the few operations the parser needs.
private struct ICU: @unchecked Sendable {
    private let regex: NSRegularExpression

    init(_ pattern: String, caseInsensitive: Bool = false) {
        // Patterns are literals, checked by the unit tests.
        regex = try! NSRegularExpression(pattern: pattern, options: caseInsensitive ? [.caseInsensitive] : [])
    }

    private func whole(_ s: String) -> NSRange { NSRange(s.startIndex..., in: s) }

    func matchesEntirely(_ s: String) -> Bool {
        guard let m = regex.firstMatch(in: s, range: whole(s)) else { return false }
        return m.range == whole(s)
    }

    func entireMatchGroups(_ s: String) -> [String]? {
        guard let m = regex.firstMatch(in: s, range: whole(s)), m.range == whole(s) else { return nil }
        return (1..<m.numberOfRanges).map { i in
            Range(m.range(at: i), in: s).map { String(s[$0]) } ?? ""
        }
    }

    func firstGroup(_ s: String) -> String? {
        guard let m = regex.firstMatch(in: s, range: whole(s)), m.numberOfRanges > 1,
              let r = Range(m.range(at: 1), in: s) else { return nil }
        return String(s[r])
    }

    func firstMatchRange(_ s: String) -> Range<String.Index>? {
        regex.firstMatch(in: s, range: whole(s)).flatMap { Range($0.range, in: s) }
    }

    func replace(_ s: String, with template: String) -> String {
        regex.stringByReplacingMatches(in: s, range: whole(s), withTemplate: template)
    }
}
