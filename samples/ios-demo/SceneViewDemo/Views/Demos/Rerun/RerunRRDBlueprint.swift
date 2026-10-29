import CryptoKit
import Foundation

/// The default layout an exported `.rrd` opens with: a Rerun blueprint, written as its own
/// store next to the recording, the way `rr.save(default_blueprint=...)` writes one.
/// Android's `RerunRrdBlueprint`, byte for byte.
///
/// - **Room** — a 3D view of `/world`: the map, the surfaces, the anchors and the camera path.
/// - **Camera** — a 2D view of `/world/camera`, only when the scene has photos: the photo
///   taken at the current time, scrubbed with the timeline.
/// - The two side by side (2 : 1), the blueprint and selection panels collapsed; the time
///   panel stays open, the replay is the point.
///
/// It is a *default* blueprint (`make_default`, not `make_active`): a layout the user already
/// saved for this application wins, and "Reset to default blueprint" in the viewer brings
/// this one back. Ids derive from the recording id, so one scene and one recording id give
/// the same bytes.
enum RerunRRDBlueprint {
    /// The timeline blueprint rows sit on, at `0`, as Rerun's SDKs write them.
    static let timeline = "blueprint"

    /// The blueprint store's id, derived from the recording's.
    static func storeId(_ recordingId: UUID) -> String {
        uuid(recordingId, "store").uuidString.lowercased()
    }

    /// One single-row chunk per blueprint entity; `hasPhotos` adds the Camera view.
    static func chunks(recordingId: UUID, hasPhotos: Bool) -> [RerunChunk] {
        let room = uuid(recordingId, "view/room")
        let camera = hasPhotos ? uuid(recordingId, "view/camera") : nil
        let root = uuid(recordingId, "container/root")
        var views = [View(id: room, kind: "3D", name: "Room", origin: "/world")]
        if let camera { views.append(View(id: camera, kind: "2D", name: "Camera", origin: "/world/camera")) }

        var chunks: [RerunChunk] = []
        chunks.append(chunk("/container/\(text(root))", [
            .u8s(container, "container_kind", "\(components).ContainerKind", [[horizontal]]),
            .strings(container, "contents", "\(components).IncludedContent", [views.map { "view/\(text($0.id))" }]),
            .floats(container, "col_shares", "\(components).ColumnShare", [camera != nil ? [2, 1] : [1]]),
        ]))
        chunks.append(chunk("/viewport", [
            .u8Vectors("\(archetypes).ViewportBlueprint", "root_container", "\(components).RootContainer",
                       [bytes(root)], size: 16),
        ]))
        for view in views {
            chunks.append(chunk("/view/\(text(view.id))", [
                .strings(viewBlueprint, "class_identifier", "\(components).ViewClass", [[view.kind]]),
                .strings(viewBlueprint, "display_name", "rerun.components.Name", [[view.name]]),
                .strings(viewBlueprint, "space_origin", "\(components).ViewOrigin", [[view.origin]]),
            ]))
            chunks.append(chunk("/view/\(text(view.id))/ViewContents", [
                .strings("\(archetypes).ViewContents", "query", "\(components).QueryExpression", [["$origin/**"]]),
            ]))
        }
        for panel in ["/blueprint_panel", "/selection_panel"] {
            chunks.append(chunk(panel, [
                .u8s("\(archetypes).PanelBlueprint", "state", "\(components).PanelState", [[collapsed]]),
            ]))
        }
        return chunks
    }

    private struct View {
        var id: UUID
        var kind: String
        var name: String
        var origin: String
    }

    private static func chunk(_ entityPath: String, _ columns: [RerunComponentColumn]) -> RerunChunk {
        RerunChunk(entityPath: entityPath, times: [0], components: columns, timeline: .blueprint)
    }

    /// Java's `UUID.nameUUIDFromBytes` (a version-3, MD5 UUID), as Android derives these ids.
    static func uuid(_ recordingId: UUID, _ name: String) -> UUID {
        let seed = "\(recordingId.uuidString.lowercased())/blueprint/\(name)"
        var digest = Array(Insecure.MD5.hash(data: Data(seed.utf8)))
        digest[6] = digest[6] & 0x0F | 0x30
        digest[8] = digest[8] & 0x3F | 0x80
        return UUID(uuid: (digest[0], digest[1], digest[2], digest[3], digest[4], digest[5], digest[6], digest[7],
                           digest[8], digest[9], digest[10], digest[11], digest[12], digest[13], digest[14], digest[15]))
    }

    /// Java's `UUID.toString`: lower-case.
    private static func text(_ uuid: UUID) -> String { uuid.uuidString.lowercased() }

    /// A UUID as Rerun stores a `RootContainer`: 16 bytes, big-endian.
    private static func bytes(_ uuid: UUID) -> [UInt8] {
        withUnsafeBytes(of: uuid.uuid) { Array($0) }
    }

    private static let archetypes = "rerun.blueprint.archetypes"
    private static let components = "rerun.blueprint.components"
    private static let container = "\(archetypes).ContainerBlueprint"
    private static let viewBlueprint = "\(archetypes).ViewBlueprint"
    /// Rerun's `ContainerKind.Horizontal`.
    private static let horizontal: UInt8 = 2
    /// Rerun's `PanelState.Collapsed`.
    private static let collapsed: UInt8 = 2
}
