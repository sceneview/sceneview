package io.github.sceneview.demo.demos.internal

/**
 * What the export sheet writes: the scan's [title], its 3D [model] when it has one (the result
 * screen's), and how to read its [capture] — the recording behind it. Either may be missing:
 * the sheet offers only the formats its source can make. Both are read off the main thread.
 * [scope] is what was scanned: the scan file carries it, so an object opens as one (#4683).
 */
class RerunExportSource(
    val title: String,
    val capture: (suspend () -> RerunCapturePack?)? = null,
    val model: (suspend () -> RerunScanModel?)? = null,
    val scope: ScanScope = ScanScope.Room,
)

/**
 * The files a scan exports to, in the sheet's order: the 3D model first — what people open in
 * Blender, three.js or any viewer — then, under "More formats", the scan's raw material.
 * USDZ is iOS-only: nothing on Android writes it or opens it.
 */
enum class RerunExportFormat(
    val extension: String,
    /** The type a share sheet or a file picker sees. Android knows no type for the others. */
    val mimeType: String,
) {
    /** The result's model ([RerunMeshGlb]); a scan without one, the recording's planes as a glTF scene. */
    Model("glb", "model/gltf-binary"),

    /** The recording's coloured points. */
    Points("ply", OCTET_STREAM),

    /** The scan itself, as this app and the iOS one open it again. */
    Scan(RerunScanFile.EXTENSION, OCTET_STREAM),

    /** The whole timeline, for the Rerun viewer. */
    Recording("rrd", OCTET_STREAM),
    ;

    /** `recorded-room.glb`: the scan's title as a file name; a scan file keeps its title as it is. */
    fun fileName(title: String): String = when (this) {
        Scan -> RerunScanFile.fileName(title)
        else -> "${RerunExportAdapter.fileStem(title)}.$extension"
    }

    companion object {
        /** The formats a source can make: the model from a model or a recording, the rest from a recording. */
        fun available(hasModel: Boolean, hasCapture: Boolean): List<RerunExportFormat> = entries.filter {
            if (it == Model) hasModel || hasCapture else hasCapture
        }

        /** The dock button that opens the sheet. */
        const val DOCK_LABEL = "Export and share this space"
        const val DOCK_CAPTION = "Export"
    }
}

private const val OCTET_STREAM = "application/octet-stream"
