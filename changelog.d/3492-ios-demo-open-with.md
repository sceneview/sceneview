<!-- category: Added -->
- **The iOS demo app opens 3D files from Files, Mail, Messages and any share sheet
  ([#3492](https://github.com/sceneview/sceneview/issues/3492)).** `.stl`, `.obj`, `.ply`,
  `.3mf`, `.usdz` and `.reality` are declared in `Info.plist`, so SceneView shows up in the
  "Open with" list for files iOS otherwise has nothing to open — Quick Look reads USDZ and
  Reality only, and an STL or a 3MF that arrives by AirDrop or comes down from a
  marketplace is a dead end on a stock iPhone. `LSSupportsOpeningDocumentsInPlace` means
  the system hands over the original file instead of copying a 500 MB scan into the app's
  Inbox.
- **The opened file answers "how big is this, really?"** The viewer shows the size in the
  file's own unit *and* in centimetres, plus the triangle count. The formats that carry no
  unit — STL, OBJ, PLY — get a mm / cm / in / m picker under the readout, because they
  store bare numbers and guessing silently is what puts a 21 cm print in the room at
  210 m. 3MF and USD state their own unit and get no picker. **View in AR** places the
  model at that real size, bottom-aligned on the detected plane — not shrunk to a tidy
  preview, which would answer a different question than the one the file was opened to
  ask.
<!-- RELEASE NOTE (maintainer-only):
     STL, OBJ, PLY and 3MF have no `UTType` constant in `UniformTypeIdentifiers`, so the
     app declares them itself. STL, OBJ and PLY are *imported* types — the app understands
     them, it does not define them — under the identifiers Apple's own ModelIO uses:
     `public.standard-tesselated-geometry-format`, `public.geometry-definition-format` and
     `public.polygon-file-format`. 3MF is *exported* (`io.3mf.3mf`, reverse-DNS on the 3MF
     Consortium's 3mf.io), because nothing else on the system declares 3MF at all: this is
     the declaration that binds the `.3mf` extension and `model/3mf` for the device. The
     filename-extension tag is what actually routes a file, so a differing identifier
     elsewhere costs nothing. USD and Reality are declared `LSHandlerRank Alternate` on
     purpose — Quick Look should stay the app a tap opens for those.
     `-open_file <path>` joins `-demo <id>` as a launch argument, so the screenshot
     pipeline can land on the opened-file screen without SpringBoard's "Open in …?"
     confirmation. The viewer copies the incoming file to `tmp` before parsing: a
     security-scoped URL is only valid between start/stop of the access, and a parse plus
     the AR session that may follow outlives that window. -->
