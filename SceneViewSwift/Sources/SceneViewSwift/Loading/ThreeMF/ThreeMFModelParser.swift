import Foundation
import simd

/// Streaming parser for one 3MF `.model` XML part.
///
/// `XMLParser` rather than a DOM: a printable model's `<triangles>` list runs to
/// hundreds of thousands of elements, and building a node tree for it costs several times
/// the memory of the arrays that are actually wanted.
///
/// **External entities are refused.** A 3MF is an untrusted file that arrives by AirDrop,
/// email or a download, and an XML parser that resolves entities is a file-read and
/// SSRF primitive (the "billion laughs" and XXE families). `externalEntityResolvingPolicy
/// = .never` plus `shouldResolveExternalEntities = false` is what keeps this a parser and
/// not a fetcher.
final class ThreeMFModelParser: NSObject, XMLParserDelegate {

    private var part = ThreeMFModelPart()
    private var currentObject: ThreeMFObject?
    /// The `<basematerials>` / `<colorgroup>` currently being filled, and its colours.
    private var currentPropertyGroupID: Int?
    private var currentPropertyColors: [SIMD4<Float>] = []
    private var parseError: Error?

    /// Parses one `.model` part.
    ///
    /// - Throws: ``ModelLoadingError/malformed(reason:)`` when the XML is invalid.
    static func parse(_ data: Data) throws -> ThreeMFModelPart {
        let delegate = ThreeMFModelParser()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.shouldResolveExternalEntities = false
        parser.externalEntityResolvingPolicy = .never
        // Namespace prefixes are kept: the Production extension's attribute is literally
        // `p:path`, and `attributeName(_:)` below matches on the local name either way.
        parser.shouldProcessNamespaces = false

        guard parser.parse() else {
            let reason = delegate.parseError.map { String(describing: $0) }
                ?? parser.parserError.map { String(describing: $0) }
                ?? "invalid XML"
            throw ModelLoadingError.malformed(reason: "3MF model XML: \(reason)")
        }
        if let parseError = delegate.parseError { throw parseError }
        return delegate.part
    }

    // MARK: - XMLParserDelegate

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName: String?,
        attributes: [String: String]
    ) {
        switch localName(elementName) {
        case "model":
            // A `unit` the spec does not define falls back to millimetres rather than
            // failing the file — the same forgiveness slicers show, and the alternative
            // is refusing to open a model over one misspelled word.
            part.unit = attributes["unit"].flatMap(ModelUnit.init(threeMFUnit:)) ?? .millimeters

        case "object":
            guard let id = integer(attributes, "id") else { return }
            var object = ThreeMFObject(id: id)
            object.name = attributes["name"] ?? ""
            object.propertyGroup = integer(attributes, "pid")
            object.propertyIndex = integer(attributes, "pindex")
            currentObject = object

        case "vertex":
            guard var object = currentObject,
                  let x = float(attributes, "x"),
                  let y = float(attributes, "y"),
                  let z = float(attributes, "z") else { return }
            object.vertices.append(SIMD3<Float>(x, y, z))
            currentObject = object

        case "triangle":
            guard var object = currentObject,
                  let v1 = integer(attributes, "v1"),
                  let v2 = integer(attributes, "v2"),
                  let v3 = integer(attributes, "v3") else { return }
            object.triangles.append(ThreeMFTriangle(
                v1: v1,
                v2: v2,
                v3: v3,
                propertyGroup: integer(attributes, "pid"),
                propertyIndex: integer(attributes, "p1")
            ))
            currentObject = object

        case "component":
            guard var object = currentObject,
                  let objectID = integer(attributes, "objectid") else { return }
            object.components.append(ThreeMFComponent(
                objectID: objectID,
                path: attributeValue(attributes, "path"),
                transform: transform(attributes)
            ))
            currentObject = object

        case "item":
            guard let objectID = integer(attributes, "objectid") else { return }
            part.buildItems.append(ThreeMFBuildItem(
                objectID: objectID,
                path: attributeValue(attributes, "path"),
                transform: transform(attributes)
            ))

        case "basematerials", "colorgroup":
            currentPropertyGroupID = integer(attributes, "id")
            currentPropertyColors = []

        case "base":
            // `<base displaycolor="#RRGGBB[AA]">` — the core spec's material colour.
            if let color = Self.color(from: attributes["displaycolor"]) {
                currentPropertyColors.append(color)
            }

        case "color":
            // `<m:color color="#RRGGBB[AA]">` — the materials extension's colour group,
            // which is what a multi-colour slicer project actually uses.
            if let color = Self.color(from: attributes["color"]) {
                currentPropertyColors.append(color)
            }

        default:
            break
        }
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName: String?
    ) {
        switch localName(elementName) {
        case "object":
            if let object = currentObject { part.objects[object.id] = object }
            currentObject = nil
        case "basematerials", "colorgroup":
            if let id = currentPropertyGroupID, !currentPropertyColors.isEmpty {
                part.propertyGroups[id] = currentPropertyColors
            }
            currentPropertyGroupID = nil
            currentPropertyColors = []
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, parseErrorOccurred error: any Error) {
        parseError = ModelLoadingError.malformed(reason: "3MF model XML: \(error)")
    }

    // MARK: - Attribute helpers

    /// Strips a namespace prefix: `p:path` → `path`, `m:color` → `color`.
    private func localName(_ name: String) -> String {
        name.split(separator: ":").last.map(String.init) ?? name
    }

    /// An attribute by local name, so `path` matches `p:path`, `P:path`, or a writer
    /// that declared the Production namespace as the default.
    private func attributeValue(_ attributes: [String: String], _ name: String) -> String? {
        if let direct = attributes[name] { return direct }
        return attributes.first { localName($0.key) == name }?.value
    }

    private func integer(_ attributes: [String: String], _ name: String) -> Int? {
        attributeValue(attributes, name).flatMap { Int($0) }
    }

    private func float(_ attributes: [String: String], _ name: String) -> Float? {
        attributeValue(attributes, name).flatMap { Float($0) }
    }

    private func transform(_ attributes: [String: String]) -> simd_float4x4 {
        attributeValue(attributes, "transform")
            .flatMap(simd_float4x4.init(threeMFTransform:)) ?? matrix_identity_float4x4
    }

    /// Parses `#RRGGBB` or `#RRGGBBAA` into linear-ish RGBA in `0...1`.
    static func color(from text: String?) -> SIMD4<Float>? {
        guard var hex = text?.trimmingCharacters(in: .whitespacesAndNewlines) else { return nil }
        if hex.hasPrefix("#") { hex.removeFirst() }
        guard hex.count == 6 || hex.count == 8, let value = UInt32(hex, radix: 16) else {
            return nil
        }
        if hex.count == 6 {
            return SIMD4<Float>(
                Float((value >> 16) & 0xFF) / 255,
                Float((value >> 8) & 0xFF) / 255,
                Float(value & 0xFF) / 255,
                1
            )
        }
        return SIMD4<Float>(
            Float((value >> 24) & 0xFF) / 255,
            Float((value >> 16) & 0xFF) / 255,
            Float((value >> 8) & 0xFF) / 255,
            Float(value & 0xFF) / 255
        )
    }
}
