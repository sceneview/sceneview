package io.github.sceneview.demo.demos.internal

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An answer to write in the room.
 *
 * @param text   Short enough for two to four handwritten lines. Words wrapped in `*asterisks*`
 *               are what [doodle] marks.
 * @param doodle The hand-drawn mark made once the words are written.
 */
internal data class AskAnswer(val text: String, val doodle: Doodle)

/** What the room tells the answer, read from ARCore when the question is asked. */
internal data class AskContext(
    /** Distance from the phone to the surface it points at, in metres, if one was hit. */
    val distanceAheadMeters: Float? = null,
)

/**
 * Where answers come from.
 *
 * The prototype answers from [ScriptedAnswerProvider], on the phone, with no network. A
 * server-side model is meant to slot in here: an implementation that posts the question (and
 * [AskContext]) to a backend that holds the key, and returns its short reply with an
 * emphasised phrase and a doodle. The app never holds a model key.
 */
internal fun interface AnswerProvider {
    suspend fun answer(question: String, context: AskContext): AskAnswer
}

/**
 * Fixed, playful answers to a handful of questions, picked by fuzzy keyword match so a
 * transcript that is slightly off still lands on the right one.
 */
internal object ScriptedAnswerProvider : AnswerProvider {

    /**
     * One scripted answer and the words that call it. A question scores one point per
     * keyword group it contains (any spelling in the group, give or take a typo), and only
     * counts for a script if it contains the first group, the topic itself. The highest
     * score at or above [minScore] wins.
     */
    internal class Script(
        val id: String,
        val keywords: List<Set<String>>,
        val minScore: Int,
        val answer: (AskContext) -> AskAnswer,
    )

    internal val SCRIPTS: List<Script> = listOf(
        Script(
            id = "best-sdk",
            keywords = listOf(
                setOf("best", "greatest", "favorite", "favourite", "top", "coolest"),
                setOf("sdk", "library", "framework", "engine", "toolkit"),
                setOf("ar", "augmented", "3d"),
            ),
            minScore = 2,
        ) { AskAnswer("*SceneView.* I may be a tiny bit biased.", Doodle.Circle) },
        Script(
            id = "sceneview",
            keywords = listOf(
                setOf("sceneview", "scene"),
                setOf("view", "you", "this", "app"),
                setOf("what", "who"),
            ),
            minScore = 2,
        ) { AskAnswer("The open-source kit *writing this* right now.", Doodle.Arrow) },
        Script(
            id = "wall",
            keywords = listOf(
                setOf("far", "distance", "close", "meters", "metres", "away"),
                setOf("wall", "that", "door", "window"),
            ),
            minScore = 2,
        ) { context -> wallAnswer(context.distanceAheadMeters) },
        Script(
            id = "cook",
            keywords = listOf(
                setOf("cook", "cooking", "dinner", "eat", "food", "recipe", "meal"),
                setOf("tonight", "today", "evening", "should"),
            ),
            minScore = 1,
        ) { AskAnswer("Pasta. The answer is always *pasta*.", Doodle.Underline) },
        Script(
            id = "weather",
            keywords = listOf(
                setOf("weather", "rain", "raining", "sunny", "umbrella", "cold", "forecast", "temperature"),
                setOf("today", "tomorrow", "outside", "will", "need"),
            ),
            minScore = 1,
        ) { AskAnswer("No idea, I live indoors. Take a *jacket* anyway.", Doodle.Underline) },
        Script(
            id = "paris",
            keywords = listOf(
                setOf("tallest", "highest", "tall", "high", "biggest"),
                setOf("building", "tower", "monument"),
                setOf("paris", "france"),
            ),
            minScore = 2,
        ) { AskAnswer("The Eiffel Tower, *330 m.* A bit taller in summer!", Doodle.Arrow) },
    )

    /** The questions the example button asks, in turn: one per script. */
    internal val EXAMPLES: List<String> = listOf(
        "What should I cook tonight?",
        "How far is that wall?",
        "What's the best AR SDK?",
        "What is SceneView?",
        "Will it rain tomorrow?",
        "What's the tallest building in Paris?",
    )

    /** For anything the script does not know. */
    internal val FALLBACK = AskAnswer("Great question. Ask me again *tomorrow!*", Doodle.Underline)

    override suspend fun answer(question: String, context: AskContext): AskAnswer =
        match(question)?.answer?.invoke(context) ?: FALLBACK

    /** The script [question] calls, or `null` when none scores enough. */
    internal fun match(question: String): Script? {
        val words = tokens(question)
        if (words.isEmpty()) return null
        fun heard(group: Set<String>) = group.any { key -> words.any { fuzzyEquals(it, key) } }
        return SCRIPTS
            .filter { script -> heard(script.keywords.first()) }
            .map { script -> script to script.keywords.count(::heard) }
            .filter { (script, score) -> score >= script.minScore }
            // Ties go to the script listed first: the specific ones come before the broad ones.
            .maxWithOrNull(compareBy<Pair<Script, Int>> { it.second }.thenByDescending { SCRIPTS.indexOf(it.first) })
            ?.first
    }

    internal fun tokens(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .replace("'", "")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }

    /**
     * Same word, give or take what a speech transcript gets wrong: one edit for a word of
     * five letters or more, two from nine. Short words must match exactly.
     */
    internal fun fuzzyEquals(heard: String, keyword: String): Boolean {
        if (heard == keyword) return true
        val longest = max(heard.length, keyword.length)
        val allowed = when {
            longest >= 9 -> 2
            longest >= 5 -> 1
            else -> 0
        }
        return allowed > 0 && editDistance(heard, keyword, allowed) <= allowed
    }

    /** Levenshtein distance, giving up once it is past [limit]. */
    internal fun editDistance(a: String, b: String, limit: Int = Int.MAX_VALUE): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
                rowMin = min(rowMin, current[j])
            }
            if (rowMin > limit) return limit + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /** "About 2.5 m" from what ARCore measured, or a round guess when it measured nothing. */
    internal fun wallAnswer(distance: Float?): AskAnswer {
        val metres = distance?.takeIf { it in MIN_DISTANCE..MAX_DISTANCE }
        return if (metres == null) {
            AskAnswer("About *3 metres.* Give or take a sofa.", Doodle.Circle)
        } else {
            val halves = (metres * 2f).roundToInt().coerceAtLeast(1)
            val shown = if (halves % 2 == 0) "${halves / 2}" else "${halves / 2}.5"
            AskAnswer("About *$shown metres.* Please don't walk into it.", Doodle.Circle)
        }
    }

    private const val MIN_DISTANCE = 0.3f
    private const val MAX_DISTANCE = 30f
}
