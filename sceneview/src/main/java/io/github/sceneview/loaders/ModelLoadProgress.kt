package io.github.sceneview.loaders

/**
 * How far a [ModelLoader.awaitModelInstance] load has got, for a progress UI.
 *
 * @property stage what the load is doing now.
 * @property fraction the whole load's progress in `[0, 1]`, never going back. The stages take fixed
 * shares of it: [Stage.Reading] up to `0.4`, [Stage.Creating] up to `0.5`, [Stage.Textures] the rest,
 * reaching `1` once every texture is finalised.
 */
data class ModelLoadProgress(val stage: Stage, val fraction: Float) {

    enum class Stage {
        /** Reading or downloading the file. Moves only when its size is known. */
        Reading,

        /** Converting it (3MF, STL, OBJ, PLY, WebP), parsing it and creating the asset. */
        Creating,

        /** Decoding and uploading the textures, the longest stage of a large local model. */
        Textures,
    }
}
