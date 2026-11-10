package io.github.sceneview.demo.demos.internal

/**
 * The steps of Build 3D model on the capture screen, in the order the save runs them (#4903).
 * Each one ends at a real boundary of the save: no step is timed, none is guessed.
 */
enum class ScanSaveStep {
    /** The capture's last depth fusion, its live model's last frame and its last photos' encoding finish. */
    Finishing,

    /** The dense map is read out as the cloud the scan keeps, and the replay's reveal worked out against it. */
    Fusing,

    /** Each plane painted from the photos that see it, then everything packed into one scan. */
    Painting,

    /** A part scanned again lined up on its scan and merged into a copy of it: only for such a part. */
    LiningUp,

    /** The built scan opened once, as the result screen will open it. */
    Checking,

    /** The scan written on the phone. */
    Writing,
}

/**
 * Where a save stands: its [step], how far through it ([inner], `0..1`, `0` for a step that
 * cannot tell), and whether it lines up a part scanned again ([linesUp]: only then is
 * [ScanSaveStep.LiningUp] one of its steps).
 */
data class ScanSaveProgress(val step: ScanSaveStep, val inner: Float = 0f, val linesUp: Boolean = false) {
    /** The steps this save runs. */
    val steps: List<ScanSaveStep>
        get() = if (linesUp) ScanSaveStep.entries else ScanSaveStep.entries - ScanSaveStep.LiningUp

    /** The step's number, from 1. A save that does not line up never reports that step; it reads as the next one. */
    val number: Int
        get() = steps.indexOf(step).takeIf { it >= 0 }?.plus(1) ?: steps.indexOf(ScanSaveStep.Checking) + 1

    val total: Int get() = steps.size

    /** The share of the save done, `0..1`: the steps finished, and the share of this one. */
    val fraction: Float get() = ((number - 1) + inner.coerceIn(0f, 1f)) / total

    /** Moved on to [next], nothing of it done yet. */
    fun at(next: ScanSaveStep): ScanSaveProgress = copy(step = next, inner = 0f)

    companion object {
        /** A save just started. */
        fun start(linesUp: Boolean) = ScanSaveProgress(ScanSaveStep.Finishing, linesUp = linesUp)
    }
}

/** What the save card says of each step (#4903): `Step 2 of 5 · Fusing 224k points…`. */
object ScanSaveCopy {
    const val FINISHING = "Finishing the last photos…"
    const val FUSING = "Fusing the points…"
    const val PAINTING = "Painting surfaces from photos…"
    const val LINING_UP = "Lining up the part…"
    const val CHECKING = "Checking the scan…"
    const val WRITING = "Saving on this phone…"

    /** The step's own words; [points] is the capture's dense count, which the fusing step names. */
    fun label(step: ScanSaveStep, points: Int): String = when (step) {
        ScanSaveStep.Finishing -> FINISHING
        ScanSaveStep.Fusing -> if (points > 0) ScanCopy.fusing(points) else FUSING
        ScanSaveStep.Painting -> PAINTING
        ScanSaveStep.LiningUp -> LINING_UP
        ScanSaveStep.Checking -> CHECKING
        ScanSaveStep.Writing -> WRITING
    }

    /** `Step 2 of 5 · Fusing 224k points…`. */
    fun line(progress: ScanSaveProgress, points: Int): String =
        "Step ${progress.number} of ${progress.total} · ${label(progress.step, points)}"
}
