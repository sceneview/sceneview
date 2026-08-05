package io.github.erkko68.filament

/**
 * TextureSampler defines how a texture is accessed during rendering.
 *
 * It specifies filtering modes, wrapping modes, and optional comparison modes for
 * texture sampling. TextureSampler objects are immutable after construction and are
 * used by MaterialInstance to configure how textures are sampled.
 */
expect class TextureSampler {
    /**
     * Texture wrapping mode for texture coordinates outside the [0..1] range.
     */
    enum class WrapMode {
        /** Clamp texture coordinates to [0..1]. Coordinates outside this range sample the edge. */
        CLAMP_TO_EDGE,
        /** Repeat the texture (tile it). */
        REPEAT,
        /** Repeat the texture in a mirrored pattern. */
        MIRRORED_REPEAT
    }

    /**
     * Minification filter specifies how texels are filtered when the texture is sampled
     * at a size smaller than its original resolution.
     */
    enum class MinFilter {
        /** Use the nearest texel. Fastest but lower quality. */
        NEAREST,
        /** Linearly interpolate between four nearest texels. */
        LINEAR,
        /** Use nearest mipmap level, nearest texel. */
        NEAREST_MIPMAP_NEAREST,
        /** Use nearest mipmap level, interpolate between texels. */
        LINEAR_MIPMAP_NEAREST,
        /** Interpolate between mipmap levels, use nearest texel. */
        NEAREST_MIPMAP_LINEAR,
        /** Interpolate between mipmap levels and texels (trilinear filtering). */
        LINEAR_MIPMAP_LINEAR,
    }

    /**
     * Magnification filter specifies how texels are filtered when the texture is sampled
     * at a size larger than its original resolution.
     */
    enum class MagFilter {
        /** Use the nearest texel. */
        NEAREST,
        /** Linearly interpolate between four nearest texels. */
        LINEAR
    }

    /**
     * Compare mode for shadow map sampling and similar depth comparisons.
     */
    enum class CompareMode {
        /** No comparison is performed. */
        NONE,
        /** Perform a depth comparison and return the result. */
        COMPARE_TO_TEXTURE
    }

    /**
     * Comparison function for depth comparison operations in COMPARE_TO_TEXTURE mode.
     */
    enum class CompareFunction {
        /** Pass if reference is less than or equal to texture value. */
        LESS_EQUAL,
        /** Pass if reference is greater than or equal to texture value. */
        GREATER_EQUAL,
        /** Pass if reference is less than texture value. */
        LESS,
        /** Pass if reference is greater than texture value. */
        GREATER,
        /** Pass if reference is equal to texture value. */
        EQUAL,
        /** Pass if reference is not equal to texture value. */
        NOT_EQUAL,
        /** Always pass (no comparison). */
        ALWAYS,
        /** Never pass. */
        NEVER
    }

    /**
     * Creates a TextureSampler with default parameters:
     * - minFilter: NEAREST
     * - magFilter: NEAREST
     * - wrapS: CLAMP_TO_EDGE
     * - wrapT: CLAMP_TO_EDGE
     * - wrapR: CLAMP_TO_EDGE
     * - compareMode: NONE
     * - compareFunction: LESS_EQUAL
     * - anisotropy: 1.0 (disabled)
     */
    constructor()

    /**
     * Creates a TextureSampler with default parameters but setting both minification and
     * magnification filters, and using CLAMP_TO_EDGE wrap mode for all axes.
     *
     * @param minMag Filtering for both minification and magnification.
     */
    constructor(minMag: MagFilter)

    /**
     * Creates a TextureSampler with filtering and wrap mode applied to all axes.
     *
     * @param minMag Filtering for both minification and magnification.
     * @param wrap Wrapping mode applied to all three axes (S, T, R).
     */
    constructor(minMag: MagFilter, wrap: WrapMode)

    /**
     * Creates a TextureSampler with separate minification and magnification filters,
     * and a wrap mode applied to all axes.
     *
     * @param min Minification filter.
     * @param mag Magnification filter.
     * @param wrap Wrapping mode applied to all three axes (S, T, R).
     */
    constructor(min: MinFilter, mag: MagFilter, wrap: WrapMode)

    /**
     * Creates a TextureSampler with separate filters and wrap modes for each axis.
     *
     * @param min Minification filter.
     * @param mag Magnification filter.
     * @param s Wrap mode for the S (horizontal) texture coordinate.
     * @param t Wrap mode for the T (vertical) texture coordinate.
     * @param r Wrap mode for the R (depth) texture coordinate.
     */
    constructor(min: MinFilter, mag: MagFilter, s: WrapMode, t: WrapMode, r: WrapMode)

    /**
     * Creates a TextureSampler configured for comparison mode (shadow mapping).
     *
     * @param mode Compare mode to use.
     */
    constructor(mode: CompareMode)

    /**
     * Creates a TextureSampler configured for comparison mode with a specific comparison function.
     *
     * @param mode Compare mode to use.
     * @param function Comparison function.
     */
    constructor(mode: CompareMode, function: CompareFunction)

    /** Minification filter. */
    var minFilter: MinFilter

    /** Magnification filter. */
    var magFilter: MagFilter

    /** Wrap mode for S (horizontal) texture coordinate. */
    var wrapModeS: WrapMode

    /** Wrap mode for T (vertical) texture coordinate. */
    var wrapModeT: WrapMode

    /** Wrap mode for R (depth) texture coordinate. */
    var wrapModeR: WrapMode

    /**
     * Anisotropic filtering amount. Should be a power-of-two. Default is 1.0 (disabled).
     * The maximum permissible value is 128.
     */
    var anisotropy: Float

    /** Comparison mode. */
    var compareMode: CompareMode

    /** Comparison function for depth comparisons. */
    var compareFunction: CompareFunction
}
