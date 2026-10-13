package io.github.sceneview.demo.demos.internal

import java.nio.ByteBuffer
import java.util.UUID

private typealias BlueprintColumn = RerunComponentColumn

/**
 * The default layout an exported `.rrd` opens with: a Rerun blueprint, written as its own store
 * next to the recording, the way `rr.save(default_blueprint=...)` writes one.
 *
 * - **Room** — a 3D view of `/world`: the map, the surfaces, the anchors and the camera path.
 * - **Camera** — a 2D view of `/world/camera`, only when the scene has photos: the photo taken
 *   at the current time, scrubbed with the timeline.
 * - The two side by side (2 : 1), the blueprint and selection panels collapsed; the time panel
 *   stays open, the replay is the point.
 *
 * It is a *default* blueprint (`make_default`, not `make_active`): a layout the user already
 * saved for this application wins, and "Reset to default blueprint" in the viewer brings this one
 * back. Ids derive from the recording id, so one scene and one recording id give the same bytes.
 */
internal object RerunRrdBlueprint {
    /** The timeline blueprint rows sit on, at `0`, as Rerun's SDKs write them. */
    const val TIMELINE = "blueprint"

    /** The blueprint store's id, derived from the recording's. */
    fun storeId(recordingId: UUID): String = uuid(recordingId, "store").toString()

    /** One single-row chunk per blueprint entity; [hasPhotos] adds the Camera view. */
    fun chunks(recordingId: UUID, hasPhotos: Boolean): List<RerunChunk> {
        val room = uuid(recordingId, "view/room")
        val camera = uuid(recordingId, "view/camera").takeIf { hasPhotos }
        val root = uuid(recordingId, "container/root")
        val views = listOfNotNull(
            View(room, "3D", "Room", "/world"),
            camera?.let { View(it, "2D", "Camera", "/world/camera") },
        )
        val chunks = ArrayList<RerunChunk>()
        chunks += chunk(
            "/container/$root",
            BlueprintColumn.u8s(
                CONTAINER, "container_kind", "$COMPONENTS.ContainerKind", listOf(byteArrayOf(HORIZONTAL)),
            ),
            BlueprintColumn.strings(
                CONTAINER, "contents", "$COMPONENTS.IncludedContent", listOf(views.map { "view/${it.id}" }),
            ),
            BlueprintColumn.floats(
                CONTAINER, "col_shares", "$COMPONENTS.ColumnShare",
                listOf(if (camera != null) floatArrayOf(2f, 1f) else floatArrayOf(1f)),
            ),
        )
        chunks += chunk(
            "/viewport",
            BlueprintColumn.u8Vectors(
                "$ARCHETYPES.ViewportBlueprint", "root_container", "$COMPONENTS.RootContainer",
                listOf(bytes(root)), UUID_SIZE,
            ),
        )
        for (view in views) {
            chunks += chunk(
                "/view/${view.id}",
                BlueprintColumn.strings(VIEW, "class_identifier", "$COMPONENTS.ViewClass", listOf(listOf(view.kind))),
                BlueprintColumn.strings(VIEW, "display_name", "rerun.components.Name", listOf(listOf(view.name))),
                BlueprintColumn.strings(VIEW, "space_origin", "$COMPONENTS.ViewOrigin", listOf(listOf(view.origin))),
            )
            chunks += chunk(
                "/view/${view.id}/ViewContents",
                BlueprintColumn.strings(
                    "$ARCHETYPES.ViewContents", "query", "$COMPONENTS.QueryExpression", listOf(listOf("\$origin/**")),
                ),
            )
        }
        for (panel in listOf("/blueprint_panel", "/selection_panel")) {
            chunks += chunk(
                panel,
                BlueprintColumn.u8s(
                    "$ARCHETYPES.PanelBlueprint", "state", "$COMPONENTS.PanelState", listOf(byteArrayOf(COLLAPSED)),
                ),
            )
        }
        return chunks
    }

    private class View(val id: UUID, val kind: String, val name: String, val origin: String)

    private fun chunk(entityPath: String, vararg columns: RerunComponentColumn) =
        RerunChunk(entityPath, columns.toList(), times = listOf(0L), timeline = RerunTimeline.BLUEPRINT)

    private fun uuid(recordingId: UUID, name: String): UUID =
        UUID.nameUUIDFromBytes("$recordingId/blueprint/$name".encodeToByteArray())

    /** A UUID as Rerun stores a `RootContainer`: 16 bytes, big-endian. */
    private fun bytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(UUID_SIZE).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    private const val ARCHETYPES = "rerun.blueprint.archetypes"
    private const val COMPONENTS = "rerun.blueprint.components"
    private const val CONTAINER = "$ARCHETYPES.ContainerBlueprint"
    private const val VIEW = "$ARCHETYPES.ViewBlueprint"
    private const val UUID_SIZE = 16

    /** Rerun's `ContainerKind.Horizontal`. */
    private const val HORIZONTAL: Byte = 2

    /** Rerun's `PanelState.Collapsed`. */
    private const val COLLAPSED: Byte = 2
}
