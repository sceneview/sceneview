package io.github.sceneview.demo.ui

/** How a [GlassChip] says it is selected. */
enum class GlassChipStyle {
    /**
     * Solid white when selected, glass otherwise: the one choice of a group, or a mode that is
     * on, has to stand out of a row that is mostly off.
     */
    Solid,

    /**
     * Glass in both states, for a row where most chips are on — a legend. A row of solid white
     * pills would be the brightest thing on the screen and outshine the scene it captions. On, the
     * chip carries a filled dot of its [swatch][GlassChip] colour and a white label; off, the dot
     * is a hollow ring and the label is muted and struck through, so the state reads from the
     * shape of the chip and not from its colour alone.
     */
    Legend,
}
