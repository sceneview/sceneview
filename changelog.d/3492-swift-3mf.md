<!-- category: Added -->
- **SceneViewSwift reads 3MF — the format 3D printing standardised on and Apple never
  shipped a reader for ([#3492](https://github.com/sceneview/sceneview/issues/3492)).**
  `MDLAsset` reads OBJ, STL, PLY and USD; Quick Look reads USDZ and Reality; nothing on
  the platform opens a `.3mf`, which is what a Bambu, Prusa or Orca user is handed and
  what MakerWorld hands back. `ModelNode.load(contentsOf:)` now opens one like any other
  file, and `ThreeMFDocument` is available directly for apps that want the geometry
  without a scene.
- **The parser covers what slicers actually write**, not just the happy path: `<model
  unit>` in every unit the core spec defines, `<mesh>`, `<components>` composed through
  their row-vector transforms, `<build><item>` placements, `<basematerials>` and the
  materials extension's `<colorgroup>` resolved **per triangle** so a multi-colour print
  comes back multi-colour, and Production-extension `p:path` references into other
  `.model` parts of the package — which is how Bambu Studio and Orca write project files,
  and the reason a reader that only ever opens `3D/3dmodel.model` shows an empty plate for
  half the files in circulation. The root part is found through `_rels/.rels` rather than
  assumed. A package whose `<build>` is empty falls back to its objects instead of
  rendering nothing. 3MF declares its own unit, so it needs no `unit:` argument — passing
  one overrides the file.
- **A 3MF is an untrusted file**, and it is opened like one. The ZIP reader is
  read-only, resolves entries by their exact recorded name (there is no path joining, so
  `../` matches nothing rather than escaping), and refuses to inflate an entry over
  256 MB. The XML parser refuses external entities outright
  (`externalEntityResolvingPolicy = .never`) — without that, opening a file received by
  AirDrop or email is a file-read primitive. An object that contains itself is reported,
  not recursed into.
<!-- RELEASE NOTE (maintainer-only):
     No third-party dependency for either half. Foundation has no public ZIP API and
     `NSFileCoordinator`'s archive support is macOS-only and disk-backed, so
     `ZipArchiveReader` reads the central directory itself and inflates with the
     `Compression` framework — `COMPRESSION_ZLIB` is Apple's name for the *raw* DEFLATE
     stream a ZIP entry stores, and reaching for a header-expecting decoder there is the
     classic way to get an empty result with no error. ZIP64 is refused with a named
     reason rather than mis-parsed; a 3MF needs it only past 4 GB or 65 535 parts.
     Two traps pinned by tests. (1) USDZ and 3MF are both ZIP archives, so the format
     sniffer cannot stop at the `PK` magic — it reads the first entry's name (`*.usd*` →
     USDZ, else 3MF). Getting this wrong hands every 3MF to RealityKit, which cannot read
     it. (2) A 3MF `transform` is twelve numbers in row-vector order (`v' = v · M`, last
     row = translation), so the file's rows become the *columns* of a `simd_float4x4`.
     Transposing them scatters every placed part, and the symptom is wrong-looking
     geometry rather than a parse error. -->
