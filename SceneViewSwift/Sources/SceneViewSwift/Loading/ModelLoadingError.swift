import Foundation

/// Why a model file could not be turned into geometry.
///
/// Every case names the thing the caller can act on — the extension that is not
/// supported, the URL that could not be read, the part of the file that is malformed —
/// because "load failed" on a file the user picked from Files is a dead end for both the
/// user and the developer.
public enum ModelLoadingError: Error, Equatable, Sendable {

    /// The file's content and extension both failed to identify a supported format.
    ///
    /// The associated value is the extension as it appeared on the file (possibly
    /// empty), so a viewer can say "SceneView cannot open .fbx files yet" and a host app
    /// can count which extensions its users actually try.
    case unsupportedFormat(fileExtension: String)

    /// The file exists but could not be opened or read.
    case unreadableFile(URL)

    /// The file is the format it claims to be, but its content is invalid.
    /// `reason` is developer-facing English, not a localized string.
    case malformed(reason: String)

    /// The file parsed cleanly but describes no drawable triangles — an STL with zero
    /// facets, a 3MF whose build section is empty, a point-cloud-only PLY. Distinct from
    /// ``malformed(reason:)``: nothing is wrong with the file, there is just nothing to
    /// show, and a viewer should say so rather than present an empty scene.
    case emptyMesh

    /// A geometry buffer could not be read in the layout it announced — a ModelIO
    /// attribute missing after conversion, an index buffer shorter than its own count.
    case unreadableGeometry(reason: String)
}

extension ModelLoadingError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .unsupportedFormat(let ext):
            let named = ext.isEmpty ? "This file" : ".\(ext) files"
            return "\(named) cannot be opened — supported formats are "
                + ModelFormat.allCases.map { ".\($0.fileExtension)" }.joined(separator: ", ")
                + "."
        case .unreadableFile(let url):
            return "Could not read \(url.lastPathComponent)."
        case .malformed(let reason):
            return "The file is damaged or invalid: \(reason)"
        case .emptyMesh:
            return "The file contains no triangles to display."
        case .unreadableGeometry(let reason):
            return "The geometry could not be read: \(reason)"
        }
    }
}
