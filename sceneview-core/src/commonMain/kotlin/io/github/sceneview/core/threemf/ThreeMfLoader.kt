package io.github.sceneview.core.threemf

/**
 * Reads **3MF** (`.3mf`, 3D Manufacturing Format) — the format every AI image-to-print flow and
 * every modern slicer emits — on all SceneView platforms.
 *
 * A 3MF is an OPC package: a ZIP whose `3D/3dmodel.model` part is XML describing triangle meshes in
 * real-world units. This object turns one into either a [ThreeMfModel] (the file's own data, for
 * inspection or a custom pipeline) or a **GLB** byte array ([toGlb]) that every SceneView renderer
 * already loads. The GLB route is the one to use: it costs one conversion and gains the whole
 * existing glTF path — materials, gestures, AR placement, the web viewer.
 *
 * Everything is pure Kotlin with no platform dependency: no `java.util.zip`, no XML library, so the
 * exact same code runs on Android, iOS and the browser.
 *
 * ```kotlin
 * // Android: open a 3MF that another app shared with you
 * val glb = ThreeMfLoader.toGlb(contentResolver.openInputStream(uri)!!.readBytes())
 * val modelInstance = modelLoader.createModelInstance(ByteBuffer.wrap(glb))
 * ```
 *
 * On Android you rarely need this directly: `ModelLoader` sniffs a 3MF buffer and converts it
 * itself, so `rememberModelInstance(modelLoader, "print.3mf")` just works.
 */
object ThreeMfLoader {

    /**
     * `true` when [bytes] looks like a 3MF: a ZIP archive containing a `3D/3dmodel.model` part.
     *
     * Cheap enough to call on every buffer before deciding which loader to use — it reads the ZIP
     * central directory, never the geometry. Returns `false` (never throws) for anything else,
     * including a plain ZIP that is not a 3MF.
     */
    fun isThreeMf(bytes: ByteArray): Boolean = runCatching {
        Zip.looksLikeZip(bytes) && Zip.entryNames(bytes).any { it.isModelPart() }
    }.getOrDefault(false)

    /**
     * Parse a `.3mf` file into its objects, meshes and build items, in the file's own units and
     * Z-up axes.
     *
     * @throws ThreeMfParseException if the archive, the model part or its XML is unreadable.
     */
    fun parse(bytes: ByteArray): ThreeMfModel = ThreeMfParser.parse(readModelPart(bytes))

    /**
     * Convert a `.3mf` file to a self-contained **GLB** (binary glTF 2.0) that any SceneView
     * renderer loads — scaled to metres, rotated Y-up, flat-shaded, one material per 3MF colour.
     *
     * @throws ThreeMfParseException if the file cannot be read.
     */
    fun toGlb(bytes: ByteArray): ByteArray = toGlb(parse(bytes))

    /** Convert an already-parsed [model] to GLB. @see toGlb */
    fun toGlb(model: ThreeMfModel): ByteArray = ThreeMfGlb.encode(model)

    private fun readModelPart(bytes: ByteArray): String {
        if (!Zip.looksLikeZip(bytes)) {
            threeMfError("Not a 3MF file: no ZIP header (a 3MF is an OPC/ZIP package)")
        }
        val part = Zip.readEntry(bytes, ModelPart)
            ?: Zip.readFirstEntry(bytes) { it.isModelPart() }
            ?: threeMfError(
                "3MF archive has no \"$ModelPart\" part " +
                    "(found: ${Zip.entryNames(bytes).joinToString()})"
            )
        return part.decodeToString()
    }

    /**
     * The conventional part path, plus the case- and separator-tolerant fallback: the OPC
     * relationship part is authoritative in theory, but every real writer uses this path and some
     * differ only in case.
     */
    private fun String.isModelPart(): Boolean =
        equals(ModelPart, ignoreCase = true) ||
            (endsWith(".model", ignoreCase = true) && startsWith("3D/", ignoreCase = true))

    private const val ModelPart = "3D/3dmodel.model"
}
