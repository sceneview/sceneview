package io.github.sceneview.demo.demos.internal

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An answer to write in the room.
 *
 * @param text   Short enough for one or two handwritten lines. Words wrapped in `*asterisks*`
 *               are what [doodle] marks.
 * @param doodle The hand-drawn mark made once the words are written.
 * @param plan   A drawing laid out in the room itself — lines on a wall, a footprint on the
 *               floor — drawn instead of the floating block. [text] then says in words what the
 *               drawing says (the note, the verdict).
 * @param reply  The answer as said in the conversation on screen, in full sentences; by
 *               default [text] without its marks.
 */
internal data class AskAnswer(
    val text: String,
    val doodle: Doodle,
    val plan: DrawingPlan? = null,
    val reply: String = text.replace("*", ""),
)

/** What the room tells the answer, read from ARCore when the question is asked. */
internal data class AskContext(
    /** Distance from the phone to the surface it points at, in metres, if one was hit. */
    val distanceAheadMeters: Float? = null,
    /** The wall ahead, the floor, the room either side of a sofa: for answers drawn in the room. */
    val room: AskRoom? = null,
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
        ) { AskAnswer("*SceneView.* I'm biased.", Doodle.Circle) },
        Script(
            id = "sceneview",
            keywords = listOf(
                setOf("sceneview", "scene"),
                setOf("view", "you", "this", "app"),
                setOf("what", "who"),
            ),
            minScore = 2,
        ) { AskAnswer("The kit *writing this*!", Doodle.Arrow) },
        Script(
            id = "wall",
            keywords = listOf(
                setOf("far", "distance", "close", "meters", "metres", "away"),
                setOf("wall", "that", "door", "window"),
            ),
            minScore = 2,
        ) { context -> wallAnswer(context.distanceAheadMeters) },
        Script(
            id = "hang",
            keywords = listOf(
                setOf("hang", "hanging", "picture", "frame", "painting", "print", "poster"),
                setOf("where", "should", "wall", "high", "height", "put"),
            ),
            minScore = 1,
        ) { context -> hangAnswer(context.room) },
        Script(
            id = "sofa",
            keywords = listOf(
                setOf("sofa", "couch", "settee", "fit", "fits"),
                setOf("here", "space", "room", "enough", "will", "would", "does"),
            ),
            minScore = 1,
        ) { context -> sofaAnswer(context.room) },
        Script(
            id = "radiator",
            keywords = listOf(
                setOf("bleed", "bleeding", "radiator", "radiators", "purge", "radiateur"),
                setOf("how", "do", "should", "this"),
            ),
            minScore = 1,
        ) { context -> radiatorAnswer(context.room) },
        Script(
            id = "cook",
            keywords = listOf(
                setOf("cook", "cooking", "dinner", "eat", "food", "recipe", "meal"),
                setOf("tonight", "today", "evening", "should"),
            ),
            minScore = 1,
        ) { AskAnswer("Pasta. *Always.*", Doodle.Underline) },
        Script(
            id = "weather",
            keywords = listOf(
                setOf("weather", "rain", "raining", "sunny", "umbrella", "cold", "forecast", "temperature"),
                setOf("today", "tomorrow", "outside", "will", "need", "like"),
            ),
            minScore = 1,
        ) { AskAnswer("No sky here. *Look out!*", Doodle.Underline, reply = "I can't see the sky from in here. The live mode reads the forecast where you are; this demo's answers are scripted.") },
        Script(
            id = "foosball",
            keywords = listOf(
                setOf("foosball", "fussball", "babyfoot", "foos"),
                setOf("winning", "win", "winner", "score", "who", "whos"),
            ),
            minScore = 1,
        ) { AskAnswer("*Me.* Obviously.", Doodle.Circle) },
        Script(
            id = "lawn",
            keywords = listOf(
                setOf("lawn", "grass", "mow", "mowing"),
                setOf("should", "today", "cut"),
            ),
            minScore = 1,
        ) { AskAnswer("Yes. *Today.*", Doodle.Underline) },
        Script(
            id = "keys",
            keywords = listOf(
                setOf("keys", "key"),
                setOf("where", "leave", "left", "lost", "find"),
            ),
            minScore = 1,
        ) { AskAnswer("*On the desk.* Again.", Doodle.Arrow) },
        Script(
            id = "paris",
            keywords = listOf(
                setOf("tallest", "highest", "tall", "high", "biggest"),
                setOf("building", "tower", "monument"),
                setOf("paris", "france"),
            ),
            minScore = 2,
        ) { AskAnswer("Eiffel Tower, *330m!*", Doodle.Arrow) },
    )

    /** The questions the example button asks, in turn: one per script. */
    internal val EXAMPLES: List<String> = listOf(
        "What should I cook tonight?",
        "How far is that wall?",
        "What's the best AR SDK?",
        "What is SceneView?",
        "What's the weather like?",
        "What's the tallest building in Paris?",
        "Who's winning at foosball?",
        "Should I mow the lawn?",
        "Where did I leave my keys?",
        "Where should I hang this picture?",
        "Will a 2 m sofa fit here?",
        "How do I bleed this radiator?",
    )

    /** For anything the script does not know. */
    internal val FALLBACK = AskAnswer("Ask me *tomorrow!*", Doodle.Underline)

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

    /**
     * "About 2.5m away!" from what ARCore measured, with an arrow down to the spot it
     * measured; a round guess, circled, when it measured nothing.
     */
    internal fun wallAnswer(distance: Float?): AskAnswer {
        val metres = distance?.takeIf { it in MIN_DISTANCE..MAX_DISTANCE }
        return if (metres == null) {
            AskAnswer("Point me *at the wall.*", Doodle.Underline, reply = "I can't measure that yet. Point the phone at the wall and move it a little, so it can see it.")
        } else {
            val halves = (metres * 2f).roundToInt().coerceAtLeast(1)
            val shown = if (halves % 2 == 0) "${halves / 2}" else "${halves / 2}.5"
            AskAnswer("About *${shown}m* away!", Doodle.Pointer, reply = "About ${shown} m, measured from the phone to the spot in the middle of the screen.")
        }
    }

    /**
     * A level line at eye level on the wall ahead, its nails and a note, drawn on the wall ARCore
     * found. With no wall to draw on, it says so.
     */
    internal fun hangAnswer(room: AskRoom?): AskAnswer {
        val wall = room?.wall ?: return AskAnswer("Point me *at the wall.*", Doodle.Underline)
        return AskAnswer(
            HangPicture.NOTE, Doodle.Underline, HangPicture.plan(wall, room.floor.y),
            reply = "Hang it with its centre at ${HangPicture.HEIGHT_LABEL}, about eye level. " +
                "I've drawn the line on the wall: two nails on it, ${(HangPicture.NAIL_SPACING * 100).roundToInt()} cm apart.",
        )
    }

    /**
     * A sofa's footprint on the floor ahead and whether it fits, from what ARCore hit either side
     * of it. Nothing hit (and the QA stage, which measures nothing) gets "Lots of room": never a
     * number that was not measured.
     */
    internal fun sofaAnswer(room: AskRoom?): AskAnswer {
        val spot = room?.sofa ?: return AskAnswer("Point me *at the floor.*", Doodle.Underline)
        val verdict = SofaFit.verdict(spot.left, spot.right)
        val measured = spot.left != null || spot.right != null
        return AskAnswer(
            verdict, Doodle.Underline, SofaFit.plan(spot, room.heading),
            reply = verdict.replace("*", "") + " I've marked its 2 m by 90 cm footprint on the floor and put one there, " +
                "to scale." + if (measured) "" else " I couldn't see a wall either side, so check the space around it.",
        )
    }

    /**
     * A circle round the bleed screw the phone is centred on, a quarter-turn arrow and the four
     * steps. With nothing hit at the centre of the screen, it asks for the screw.
     */
    internal fun radiatorAnswer(room: AskRoom?): AskAnswer {
        val spot = room?.wall ?: return AskAnswer("Point me *at the screw.*", Doodle.Underline)
        return AskAnswer(
            "Open it a *quarter turn.*", Doodle.Underline, RadiatorBleed.plan(spot, room.camera),
            reply = "Heating off first, and a cloth under the screw. Then a quarter turn anticlockwise " +
                "with a radiator key: air hisses out, then water. Close it as soon as water comes.",
        )
    }

    private const val MIN_DISTANCE = 0.3f
    private const val MAX_DISTANCE = 30f
}
