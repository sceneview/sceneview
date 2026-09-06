<!-- category: Added -->
- **The demo app is now an "Open with" target for `.3mf`, `.glb` and `.gltf` — a print shared out
  of ChatGPT opens in 3D, then in AR ([#3482](https://github.com/sceneview/sceneview/issues/3482)).**
  Reading 3MF in the SDK only matters if a file can reach it: on Android a `.3mf` sitting in
  Downloads or arriving from a chat has nowhere to go, because no installed app claims it. The
  demo now declares `ACTION_VIEW` and `ACTION_SEND` filters for all three formats and appears in
  the chooser, so the file lands in the viewer under its own name and the dock's **View in AR**
  carries it through to placement at its real printed size.
  **Android's file typing is unreliable, so the file's own bytes decide.** Measured on an emulator:
  a `.3mf` arriving through the share sheet has `application/octet-stream` as its type *and* no
  queryable display name at all, so both metadata signals are blank and a metadata-only check
  refuses a file the SDK reads perfectly. Incoming files are therefore sniffed — glTF magic, and
  for a ZIP the SDK's own `ThreeMfLoader.isThreeMf`, so a `.docx` or a `.jar` is not claimed just
  for starting with `PK` — with the declared name and MIME kept as the fallback. The file is copied
  into the app's cache before use: a `content://` read grant is scoped to the launching intent and
  would expire under the viewer → AR navigation, and only the previous file is kept.
