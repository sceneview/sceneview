package io.github.sceneview.demo.telemetry

import java.text.BreakIterator
import java.util.Locale

/**
 * What a search field may send as `search_term`: the only typed text this app's analytics
 * carries. **Shared rule for rule with the iOS demo** (`DemoAnalytics.swift`, `SearchTerm`).
 *
 * The term is trimmed, its whitespace collapsed, lowercased and cut to [MAX_LENGTH]
 * characters — user-perceived characters (grapheme clusters, Swift's `Character`), so a cut
 * never splits an emoji. Anything shorter than [MIN_LENGTH] is dropped (a single letter is a
 * keystroke, not a search), and anything that looks like an email address, a link, a long
 * number or a phone number written with separators is replaced by [REDACTED] rather than sent.
 */
object SearchTerm {
    const val MIN_LENGTH = 2
    const val MAX_LENGTH = 50
    const val REDACTED = "(redacted)"

    /** How long a field must stay still before its query counts as a search. */
    const val SETTLE_MS = 1_500L

    private val whitespace = Regex("\\s+")
    private val longNumber = Regex("\\d{5,}")

    /** What people put between the digits of a phone number: `06 12…`, `+33 6…`, `06.12…`, `(0)6-12…`. */
    private val phoneSeparators = Regex("[\\s.\\-+()]")
    private val phoneDigits = Regex("\\d{6,}")

    /** The term to send for [raw], or `null` when nothing should be sent. */
    fun normalize(raw: String): String? {
        val term = raw.trim().replace(whitespace, " ").lowercase(Locale.ROOT)
        val characters = graphemes(term)
        if (characters.size < MIN_LENGTH) return null
        if (looksPersonal(term)) return REDACTED
        return characters.take(MAX_LENGTH).joinToString("").trimEnd()
    }

    /**
     * An email address, a link, a long number (an order id) or a phone number — six digits or
     * more once the separators people type between them are dropped: never sent as typed.
     */
    private fun looksPersonal(term: String): Boolean =
        listOf("@", "http", "www.").any { it in term } ||
            longNumber.containsMatchIn(term) ||
            phoneDigits.containsMatchIn(term.replace(phoneSeparators, ""))

    /** [text] split into user-perceived characters, so a cut never lands inside an emoji. */
    private fun graphemes(text: String): List<String> {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        val out = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            out += text.substring(start, end)
            start = end
            end = iterator.next()
        }
        return out
    }

    /** `result_bucket` for [count] results, `error` when the search never answered (`null`). */
    fun resultBucket(count: Int?): String = when {
        count == null -> "error"
        count <= 0 -> "0"
        count == 1 -> "1"
        count <= 5 -> "2-5"
        count <= 20 -> "6-20"
        else -> "21+"
    }
}

/** Which field the search was typed in. */
enum class SearchSurface(val value: String) {
    /** The Home grid's sample filter — local, instant. */
    Samples("samples"),

    /** The Explore tab's online model catalogs. */
    Models("models"),
}

/**
 * Turns settled queries into `search` events, once each: a field that settles twice on the same
 * term (a recomposition, a result tapped right after the settle fired) counts once. Each field
 * remembers its own last search, so moving to the other field and back does not count the
 * same term again. Typing a different term counts again, and so does the first answer after
 * a failure: a search that ended in `error` never hides the success a retry brings.
 */
class SearchLogger(private val log: (AnalyticsEvent) -> Unit) {
    /** Per field, the last term logged and whether that search failed. */
    private val last = mutableMapOf<SearchSurface, Pair<String, Boolean>>()

    /** Logs [raw] as a search on [surface] that found [resultCount] results (`null`: failed). */
    @Synchronized
    fun settled(surface: SearchSurface, raw: String, resultCount: Int?): Boolean {
        val term = SearchTerm.normalize(raw) ?: return false
        val key = term to (resultCount == null)
        if (last[surface] == key) return false
        last[surface] = key
        log(AnalyticsEvent.Search(term, surface, SearchTerm.resultBucket(resultCount)))
        return true
    }

    /** The field on [surface] was emptied: the next search there counts, even if it repeats. */
    @Synchronized
    fun cleared(surface: SearchSurface) {
        last.remove(surface)
    }
}
