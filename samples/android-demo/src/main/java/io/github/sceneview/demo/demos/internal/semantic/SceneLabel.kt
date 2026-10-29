package io.github.sceneview.demo.demos.internal.semantic

/**
 * What a recognised part of a room is. The furniture comes from a 2D detector's classes
 * ([ofDetector]); the doors and the windows from the holes in the walls ([WallOpenings]) — the
 * app's detector (COCO classes) has none for either, a later one would map its own here.
 */
enum class SceneLabel(val wireName: String) {
    Bed("bed"),
    Sofa("sofa"),
    Chair("chair"),
    Table("table"),
    Bench("bench"),
    Toilet("toilet"),
    Sink("sink"),
    Refrigerator("refrigerator"),
    Oven("oven"),
    Microwave("microwave"),
    Television("television"),
    Plant("plant"),
    Door("door"),
    Window("window"),
    ;

    /** A hole in a wall rather than something standing in the room. */
    val isOpening: Boolean get() = this == Door || this == Window

    companion object {
        /**
         * The room part a detector's class [name] is, `null` for a class that is none: a person,
         * a cup, a car. COCO's names and the obvious synonyms of a later model's.
         */
        fun ofDetector(name: String): SceneLabel? = when (name.lowercase()) {
            "bed" -> Bed
            "couch", "sofa" -> Sofa
            "chair" -> Chair
            "dining table", "table", "desk" -> Table
            "bench" -> Bench
            "toilet" -> Toilet
            "sink" -> Sink
            "refrigerator", "fridge" -> Refrigerator
            "oven", "stove" -> Oven
            "microwave" -> Microwave
            "tv", "television", "tvmonitor" -> Television
            "potted plant", "pottedplant", "plant" -> Plant
            "door" -> Door
            "window" -> Window
            else -> null
        }

        fun ofWire(name: String?): SceneLabel? = entries.firstOrNull { it.wireName == name }
    }
}
