<!-- category: Added -->
- **SceneViewSwift opens STL, OBJ and PLY, at real-world size
  ([#3492](https://github.com/sceneview/sceneview/issues/3492)).** `ModelNode.load` was
  USDZ and Reality only, which meant the Apple side could not open the files people
  actually receive — a slicer's `.stl`, a scanner's `.ply`, a marketplace's `.obj`. It now
  reads all three through ModelIO and is **one entry point for every format**:
  `ModelFormat.sniff(contentsOf:)` reads the file's own bytes before believing its
  extension, because a model that arrived through a share sheet, AirDrop or a download
  routinely has the wrong one. `ModelLoadingError.unsupportedFormat` carries the extension
  the user tried, so a viewer can say which format it was handed instead of "load failed".
- **A unit, because these formats do not have one.** STL, OBJ and PLY store bare numbers:
  the same coordinates mean millimetres out of a slicer and metres out of a photogrammetry
  pipeline, and RealityKit is metric — so loading either without saying which is meant puts
  a 21 cm print in the room at 210 m. `ModelNode.load(contentsOf:unit:)` takes a
  `ModelUnit` (µm / mm / cm / in / ft / m), defaulting to **millimetres for STL** and metres
  for OBJ and PLY, and bakes the conversion into the vertex positions so a caller's later
  `.scale(_:)` is theirs alone. `ModelFormat.carriesUnit` is `false` for exactly those
  three — the signal to offer a unit picker rather than guess.
- **`MeshAsset` — geometry you can measure before you render it.** Parsing produces plain
  Swift arrays with no RealityKit dependency, so `MeshAsset.load(contentsOf:)` runs off the
  main actor and answers "how big is this, really?" (`boundsInMeters`, `triangleCount`)
  before an entity exists. `ModelNode(_ asset:)` turns it into a `ModelEntity` with one
  physically-based material per part; OBJ `.mtl` base colour, metallic and roughness are
  carried across, quad faces are triangulated, and PLY per-vertex colours survive on
  `MeshGeometry.colors` (averaged into the material tint, since RealityKit's
  `MeshDescriptor` has no vertex-colour channel).
<!-- RELEASE NOTE (maintainer-only):
     Two ModelIO behaviours are worked around here rather than inherited, both measured on
     Xcode 26.3 and pinned by tests. (1) `MDLMesh.addNormals` UNWELDS the mesh — a
     4-vertex PLY quad comes back with 6 vertices and a rewritten index buffer, so any
     vertex count read before the call is wrong afterwards. Missing normals are generated
     by `MeshGeometry.generatingNormals()` instead, which keeps shared vertices shared.
     (2) `vertexAttributeData(forAttributeNamed:as:)` converts the component *type* but not
     the component *count*: asking a float3 colour attribute for `.float4` returns a buffer
     sized and strided for float4 still holding tightly packed float3 data, so every vertex
     after the first reads a third of a vertex out of step and a red/green/blue/white PLY
     decodes as red/red/white/black. Attributes are now always requested in their native
     component count and widened in Swift.
     Also deliberate: the binary-STL test fixture's 80-byte header starts with the word
     `solid`, which is what real exporters write. A prefix test alone reads such a file as
     ASCII and yields an empty mesh; the sniffer uses the size arithmetic
     (84 + 50 × triangleCount == fileSize) first. -->
