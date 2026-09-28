<!-- category: Added -->
- **Export a Rerun session from the Android demo as `.rrd`, `.glb` or `.ply`, and open `.rrd` files ([#4079](https://github.com/sceneview/sceneview/issues/4079)).**
  - The replay's new **Export** button writes the session on the phone in three open formats:
    - a Rerun recording (`.rrd`);
    - a glTF scene (`.glb`);
    - a coloured point cloud (`.ply`).
  - Share one file, or all three at once.
  - The app reads `.rrd` files back: its own exports and the iOS demo's. Open them from **Open file**, open-with or share-to. Each opened file is kept in **Your sessions**.
  - LZ4-compressed recordings are refused with a clear message.
