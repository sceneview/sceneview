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
 * APK assets on every assemble (`bundle<Variant>ChangelogAsset` in
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

/**
 * One changelog bullet, carrying the identity the "since you last tested"
 * surface needs on top of what the card needs.
 *
 * **[id] is why this type exists.** It is a hash of the entry's *normalised
 * text*, deliberately not of its position or its section. An entry is written
 * once as a `changelog.d/` fragment, shows up under `## Unreleased`, and later
 * moves verbatim into a `## vX.Y.Z` section when the release is collated — the
 * same sentence in two different places over its life. Hashing the text means
 * an entry acknowledged while unreleased stays acknowledged after it ships,
 * instead of being presented a second time under its new version heading.
 */
data class WhatsNewEntry(
    val id: String,
    val category: WhatsNewCategory,
    /**
     * The headline with inline code spans preserved, for rendering. Bold
     * markers are already resolved (the whole headline is bold by convention,
     * so keeping them would just render every entry bold).
     */
    val text: String,
    /** First `[#N]` issue/PR reference found in the bullet, if any. */
    val issueNumber: Int? = null,
) {
    /** [text] with markdown stripped entirely — for matching and previews. */
    val plainText: String get() = text.replace("`", "")
}

/**
 * One `##` section of the changelog: a released version, or the synthesised
 * `## Unreleased` block the demo build fills from `changelog.d/`.
 */
data class WhatsNewSection(
    /** Version without the leading `v`, or `null` for `## Unreleased`. */
    val version: String?,
    val date: String?,
    val title: String?,
    val entries: List<WhatsNewEntry>,
) {
    val isUnreleased: Boolean get() = version == null
}

/** Asset name the demo build copies the repo-root `CHANGELOG.md` to. */
const val WHATS_NEW_ASSET = "CHANGELOG.md"

/** How many releases the What's new sheet shows. */
const val WHATS_NEW_MAX_RELEASES = 3

/**
 * Ceiling on how many released sections the "since you last tested" surface
 * will read back. A tester who skipped more than this many releases is being
 * shown a summary either way; the cap keeps a ~1 MB file from being parsed in
 * full on a cold start.
 */
const val WHATS_NEW_MAX_SINCE_RELEASES = 20

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
 * Reads the bundled changelog asset and parses it into sections, unreleased
 * block included. Same failure contract as [loadWhatsNew]: blocking I/O, and
 * any failure degrades to an empty list rather than crashing the app.
 */
fun loadWhatsNewSections(
    assets: AssetManager,
    maxReleases: Int = WHATS_NEW_MAX_SINCE_RELEASES,
): List<WhatsNewSection> = runCatching {
    assets.open(WHATS_NEW_ASSET).bufferedReader().use { it.readText() }
}.map { parseChangelogSections(it, maxReleases) }.getOrDefault(emptyList())

/**
 * Parses [markdown] (the collated `CHANGELOG.md`) into at most [maxReleases]
 * releases, newest first (the file is already newest-first). Stops reading as
 * soon as enough releases are complete — the file is ~1 MB and only its head
 * is ever needed.
 */
fun parseWhatsNew(
    markdown: String,
    maxReleases: Int = WHATS_NEW_MAX_RELEASES,
): List<WhatsNewRelease> = parseChangelogSections(markdown, maxReleases)
    .filterNot { it.isUnreleased }
    .take(maxReleases)
    .map { section ->
        WhatsNewRelease(
            version = section.version.orEmpty(),
            date = section.date,
            title = section.title,
            highlights = section.entries.map {
                WhatsNewHighlight(it.category, it.plainText, it.issueNumber)
            },
        )
    }

/**
 * Parses [markdown] into its `##` sections, newest first, keeping the
 * `## Unreleased` block (which the demo build fills from `changelog.d/` — see
 * `BundleChangelogAsset`) when it carries entries.
 *
 * [maxReleases] caps *released* sections only; the unreleased block is always
 * free. Parsing stops as soon as the cap is reached — the file is ~1 MB and
 * only its head is ever needed.
 */
fun parseChangelogSections(
    markdown: String,
    maxReleases: Int = WHATS_NEW_MAX_RELEASES,
): List<WhatsNewSection> {
    val sections = mutableListOf<WhatsNewSection>()
    var releaseCount = 0

    var open = false
    var version: String? = null
    var date: String? = null
    var title: String? = null
    var entries = mutableListOf<WhatsNewEntry>()
    var category: WhatsNewCategory? = null

    fun flush() {
        // An empty `## Unreleased` is the committed placeholder — nothing to
        // show. A released section with no user-facing bullets is still a real
        // release and keeps its heading, as it always has.
        if (open && !(version == null && entries.isEmpty())) {
            sections += WhatsNewSection(version, date, title, entries.toList())
            if (version != null) releaseCount++
        }
        open = false
        version = null
        date = null
        title = null
        entries = mutableListOf()
        category = null
    }

    for (line in markdown.lineSequence()) {
        when {
            line.startsWith("## ") -> {
                flush()
                if (releaseCount >= maxReleases) break
                val header = parseReleaseHeader(line)
                when {
                    header != null -> {
                        open = true
                        version = header.version
                        date = header.date
                        title = header.title
                    }
                    line.removePrefix("## ").trim().equals("unreleased", ignoreCase = true) ->
                        open = true // version stays null — the unreleased block.
                    // else: a non-release prose section; skip to the next header.
                }
            }

            !open -> continue

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
                parseEntry(line, cat)?.let { entries += it }
            }
        }
    }
    flush()
    return sections
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

private fun parseEntry(line: String, category: WhatsNewCategory): WhatsNewEntry? {
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
    // Code spans survive into `text` so the sheet can render them as code;
    // `plainText` drops them for the card, which has always shown them plain.
    val text = leading
        .replace(ISSUE_LINK_GROUP, "")
        .replace(MARKDOWN_LINK) { it.groupValues[1] }
        .replace("**", "")
        .trim()
        .trimEnd('.', ':')
        .trim()

    return text.takeIf { it.isNotEmpty() }
        ?.let { WhatsNewEntry(whatsNewEntryId(it), category, it, issueNumber) }
}

/**
 * Stable identity for a changelog entry, derived from its text alone.
 *
 * Text is the only thing an entry keeps when it moves from a `changelog.d/`
 * fragment into a released section, so it is the only thing the id can be
 * built from if "already acknowledged" is to survive a release. Normalisation
 * absorbs the cosmetic differences that move does introduce — case, collapsed
 * whitespace, and the `([#123](…))` reference the collator may attach.
 *
 * FNV-1a rather than [String.hashCode] because this value is *persisted*: a
 * hash written to disk must be defined by this file, not by a JVM
 * implementation detail that a future runtime is free to change.
 */
internal fun whatsNewEntryId(text: String): String {
    val normalised = text
        .replace(ISSUE_LINK_GROUP, "")
        .replace(ISSUE_REF, "")
        .replace("`", "")
        .replace("**", "")
        .lowercase()
        .replace(Regex("""\s+"""), " ")
        .trim()
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
    for (char in normalised) {
        hash = hash xor char.code.toLong()
        hash *= 0x100000001b3L // FNV-1a 64-bit prime
    }
    return hash.toULong().toString(16)
}
