package io.github.sceneview.demo.whatsnew

import android.content.res.AssetManager

/**
 * "What's new" data layer — parses the repo's release notes into the compact
 * structure the Samples-tab card and sheet render.
 *
 * **Why the source is `CHANGELOG.md` and not a hand-maintained list.** The
 * entire point of the What's new surface is to show *recently shipped or
 * fixed* work at a glance. A curated in-app list would rot the first time an
 * author forgot to update it. `CHANGELOG.md` cannot rot the same way: it is
 * regenerated on every release by `.claude/scripts/collate-changelog.sh` from
 * the per-PR `changelog.d/` fragments, and the demo build copies it into the
 * APK assets on every assemble (`bundleChangelogAsset` in
 * `samples/android-demo/build.gradle`). Shipping a release *is* updating this
 * screen. `WhatsNewAssetIntegrityTest` fails the build if the asset goes
 * missing or the collator's format drifts past what this parser understands.
 *
 * The parser is deliberately tolerant of the three header shapes the
 * changelog has historically carried:
 *  - `## v4.30.0 — 2026-08-12 — Human-readable release title`
 *  - `## v4.28.0 — 2026-08-10`
 *  - `## v4.23.0 — Human-readable release title (2026-07-18)`  (pre-4.24 form)
 *
 * Only user-visible categories surface (`Added`, `Fixed`, `Changed`,
 * `Performance`, `Removed`); `Tests` and `Docs` are engineering bookkeeping
 * and are skipped. The `## Unreleased` placeholder is skipped too — at build
 * time it is empty by construction (fragments live in `changelog.d/` until
 * release), and half-collated legacy bullets must never show as "shipped".
 */

/** A single changelog bullet, reduced to its bold headline. */
data class WhatsNewHighlight(
    val category: WhatsNewCategory,
    val headline: String,
    /** First `[#N]` issue/PR reference found in the bullet, if any. */
    val issueNumber: Int? = null,
)

/** User-visible changelog categories, in the order the sheet renders them. */
enum class WhatsNewCategory { Added, Fixed, Changed, Performance, Removed }

/** One released version parsed from `CHANGELOG.md`. */
data class WhatsNewRelease(
    /** Version without the leading `v`, e.g. `4.30.0`. */
    val version: String,
    /** ISO date (`2026-08-12`) when the header carried one. */
    val date: String?,
    /** Human-readable release title when the header carried one. */
    val title: String?,
    val highlights: List<WhatsNewHighlight>,
)

/** Asset name the demo build copies the repo-root `CHANGELOG.md` to. */
const val WHATS_NEW_ASSET = "CHANGELOG.md"

/** How many releases the What's new sheet shows. */
const val WHATS_NEW_MAX_RELEASES = 3

private val VERSION = Regex("""\d+(\.\d+)*""")
private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
private val TITLE_WITH_TRAILING_DATE = Regex("""^(.*\S)\s*\((\d{4}-\d{2}-\d{2})\)$""")
private val ISSUE_REF = Regex("""\[#(\d+)]""")

/** ` ([#123](url))` / ` ([#1](u), [#2](u))` groups appended to headlines. */
private val ISSUE_LINK_GROUP = Regex("""\s*\((?:\[#\d+]\([^)]*\)(?:,\s*)?)+\)""")
private val MARKDOWN_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")

/**
 * Reads the bundled changelog asset and parses it. Blocking I/O — call from
 * a background dispatcher. Any failure (asset missing, unreadable) degrades
 * to an empty list: the What's new card simply does not render, it never
 * crashes the Samples tab.
 */
fun loadWhatsNew(
    assets: AssetManager,
    maxReleases: Int = WHATS_NEW_MAX_RELEASES,
): List<WhatsNewRelease> = runCatching {
    assets.open(WHATS_NEW_ASSET).bufferedReader().use { it.readText() }
}.map { parseWhatsNew(it, maxReleases) }.getOrDefault(emptyList())

/**
 * Parses [markdown] (the collated `CHANGELOG.md`) into at most [maxReleases]
 * releases, newest first (the file is already newest-first). Stops reading as
 * soon as enough releases are complete — the file is ~1 MB and only its head
 * is ever needed.
 */
fun parseWhatsNew(
    markdown: String,
    maxReleases: Int = WHATS_NEW_MAX_RELEASES,
): List<WhatsNewRelease> {
    val releases = mutableListOf<WhatsNewRelease>()

    var version: String? = null
    var date: String? = null
    var title: String? = null
    var highlights = mutableListOf<WhatsNewHighlight>()
    var category: WhatsNewCategory? = null

    fun flush() {
        val v = version ?: return
        releases += WhatsNewRelease(v, date, title, highlights.toList())
        version = null
        date = null
        title = null
        highlights = mutableListOf()
        category = null
    }

    for (line in markdown.lineSequence()) {
        when {
            line.startsWith("## ") -> {
                flush()
                if (releases.size >= maxReleases) break
                val header = parseReleaseHeader(line)
                if (header != null) {
                    version = header.version
                    date = header.date
                    title = header.title
                }
                // else: `## Unreleased` or a non-release section — `version`
                // stays null and every line until the next header is skipped.
            }

            version == null -> continue

            line.startsWith("### ") ->
                category = when (line.removePrefix("### ").trim().lowercase()) {
                    "added" -> WhatsNewCategory.Added
                    "fixed" -> WhatsNewCategory.Fixed
                    "changed" -> WhatsNewCategory.Changed
                    "performance" -> WhatsNewCategory.Performance
                    "removed" -> WhatsNewCategory.Removed
                    // Tests / Docs / anything future — not user-facing.
                    else -> null
                }

            // Top-level bullets only: continuation lines are indented and
            // never carry the headline.
            line.startsWith("- ") -> category?.let { cat ->
                parseHighlight(line, cat)?.let { highlights += it }
            }
        }
    }
    flush()
    return releases.take(maxReleases)
}

private data class ReleaseHeader(val version: String, val date: String?, val title: String?)

private fun parseReleaseHeader(line: String): ReleaseHeader? {
    // Header parts are separated by em dashes: `## v4.30.0 — 2026-08-12 — Title`.
    val parts = line.removePrefix("## ").trim().split(" — ").map { it.trim() }
    val version = parts.first().removePrefix("v")
    if (!VERSION.matches(version)) return null // `## Unreleased`, prose headers…

    var date: String? = null
    val titleParts = mutableListOf<String>()
    for (part in parts.drop(1)) {
        val titled = TITLE_WITH_TRAILING_DATE.matchEntire(part)
        when {
            ISO_DATE.matches(part) -> date = part
            // Pre-4.24 form: `Title (2026-07-18)`.
            titled != null -> {
                titleParts += titled.groupValues[1]
                date = titled.groupValues[2]
            }
            part.isNotEmpty() -> titleParts += part
        }
    }
    return ReleaseHeader(
        version = version,
        date = date,
        // A title that itself contained an em dash was split above — rejoin.
        title = titleParts.joinToString(" — ").ifEmpty { null },
    )
}

private fun parseHighlight(line: String, category: WhatsNewCategory): WhatsNewHighlight? {
    val raw = line.removePrefix("- ").trim()
    if (raw.isEmpty()) return null

    val issueNumber = ISSUE_REF.find(raw)?.groupValues?.get(1)?.toIntOrNull()

    // Fragments lead with a bold headline (`**Headline.** long prose`) —
    // that is the at-a-glance text. Unbolded legacy bullets fall back to
    // their first sentence.
    val leading = if (raw.startsWith("**")) {
        val close = raw.indexOf("**", startIndex = 2)
        if (close > 2) raw.substring(2, close) else raw
    } else {
        val sentenceEnd = raw.indexOf(". ")
        if (sentenceEnd > 0) raw.substring(0, sentenceEnd + 1) else raw
    }
    val headline = leading
        .replace(ISSUE_LINK_GROUP, "")
        .replace(MARKDOWN_LINK) { it.groupValues[1] }
        .replace("**", "")
        .replace("`", "")
        .trim()
        .trimEnd('.', ':')
        .trim()

    return headline.takeIf { it.isNotEmpty() }
        ?.let { WhatsNewHighlight(category, it, issueNumber) }
}
