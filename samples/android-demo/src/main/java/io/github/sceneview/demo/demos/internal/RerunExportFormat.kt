package io.github.sceneview.demo.demos.internal

/** The session the export sheet writes: its title, and how to read its capture (IO). */
class RerunExportSource(val title: String, val capture: suspend () -> RerunCapturePack?)

/**
 * The open formats a session exports to, as the export sheet lists them, in the iOS demo's words
 * (`RerunExportFormat`). USDZ is iOS-only: nothing on Android writes it or opens it.
 */
enum class RerunExportFormat(
    val extension: String,
    val title: String,
    /** Where the file opens: what a user reaches for it with. */
    val detail: String,
    /** The type a share sheet sees. Android knows no type for `.rrd` or `.ply`. */
    val mimeType: String,
) {
    Rrd("rrd", "Rerun recording", "The whole timeline, for the Rerun viewer", "application/octet-stream"),
    Glb("glb", "glTF scene", "Blender, three.js, any glTF tool", "model/gltf-binary"),
    Ply("ply", "Point cloud", "Coloured points for MeshLab, Open3D", "application/octet-stream"),
    ;

    /** `recorded-room.glb`: the session's title as a file name. */
    fun fileName(title: String): String = "${RerunExportAdapter.fileStem(title)}.$extension"

    companion object {
        const val SHEET_TITLE = "Export your space — open formats, ready for your AI tools."
        const val SHARE_ALL = "Share all three"
        const val PREPARING = "Preparing files…"
        const val PRIVACY = "Everything stays on your phone until you share it."
        const val FAILED = "Could not make this file"

        /** The dock button that opens the sheet. */
        const val DOCK_LABEL = "Export and share this space"
        const val DOCK_CAPTION = "Export"
    }
}
