package io.github.sceneview.demo.demos.internal.spatial

/** Deliberately small offline grammar. Unrecognised words or missing axes never imply dimensions. */
object SpatialSentenceParser {
    private const val NUMBER = "[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)"
    private const val UNIT = "mm|cm|m"
    private val pair = Regex("($NUMBER)\\s*($UNIT)?\\s*(?:×|x|by)\\s*($NUMBER)\\s*($UNIT)\\b")
    private val axis = Regex("($NUMBER)\\s*($UNIT)\\s*(wide|high|tall|deep)\\b")
    private val noun = Regex("\\b(desk|box|table|shelf)\\b")
    private val filler = Regex("\\b(show|a|an|here|on|the|floor|table|and)\\b|[, .!?]")

    fun parse(sentence: String): SpatialResult {
        var rest = sentence.lowercase().trim()
        val label = noun.find(rest)?.value ?: return SpatialResult.Error(SpatialError.UnknownSentence)
        rest = rest.replaceFirst(Regex("\\b$label\\b"), " ")
        val values = mutableMapOf<String, Float>()
        fun metres(value: String, unit: String) = value.toFloatOrNull()?.times(
            when (unit) { "mm" -> 0.001f; "cm" -> 0.01f; else -> 1f }
        ) ?: Float.NaN
        val pairs = pair.findAll(rest).toList()
        if (pairs.size > 1) return SpatialResult.Error(SpatialError.UnknownSentence)
        pairs.singleOrNull()?.let {
            values["wide"] = metres(it.groupValues[1], it.groupValues[2].ifEmpty { it.groupValues[4] })
            values["deep"] = metres(it.groupValues[3], it.groupValues[4])
            rest = rest.replaceRange(it.range, " ")
        }
        val axes = axis.findAll(rest).toList()
        for (match in axes) {
            val key = match.groupValues[3].let { if (it == "tall") "high" else it }
            if (key in values) return SpatialResult.Error(SpatialError.UnknownSentence)
            values[key] = metres(match.groupValues[1], match.groupValues[2])
        }
        rest = axis.replace(rest, " ")
        val table = Regex("\\bon (?:a |the )?table\\b").containsMatchIn(rest)
        val floor = Regex("\\bon (?:a |the )?floor\\b").containsMatchIn(rest)
        if (table && floor || filler.replace(rest, "").isNotBlank() ||
            values.keys != setOf("wide", "high", "deep")) {
            return SpatialResult.Error(SpatialError.UnknownSentence)
        }
        val spec = SpatialAnswerSpec(
            v = 1,
            subject = SpatialObject.Box(listOf(values.getValue("wide"), values.getValue("high"), values.getValue("deep"))),
            dimensionSource = DimensionSource.USER,
            placement = if (table) SpatialPlacement.TABLE else SpatialPlacement.FLOOR,
            label = label.replaceFirstChar { it.uppercase() },
        )
        return spec.validate()?.let { SpatialResult.Error(it) } ?: SpatialResult.Success(spec)
    }
}
