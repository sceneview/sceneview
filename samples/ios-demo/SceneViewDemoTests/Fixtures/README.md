# Android dense-only RRD fixture (pending generation)

No binary fixture has been generated or committed. The current Swift tests construct
Android-compatible columns with the Swift Arrow encoder; they do not prove interop.

Android reference: `RerunScanV2Test`, test
`a v2 scan's GLB and RRD carry the dense cloud as world-dense`, calls
`RerunRrdWriter.write(scene, FakeRerunImageCodec)` (line 162). That test currently
uses a random recording ID and does not save its bytes. The writer is deterministic
with a fixed ID (`RerunRrdTest`: `one scene and one recording id always give the same bytes`).

When JVM execution is available, use this expression from a test in the same Android
package, then save the result to `samples/ios-demo/SceneViewDemoTests/Fixtures/android-dense-only.rrd`:

```kotlin
val scene = RerunExportScene(
    title = "", lens = null, points = emptyList(), pointColors = emptyList(),
    cameraPath = emptyList(), keyframes = emptyList(), images = emptyMap(),
    planes = emptyList(), anchors = emptyList(),
    dense = DenseCloud(
        floatArrayOf(0f, 0f, 0f, 1f, 2f, 3f),
        intArrayOf(0xFF0A141E.toInt(), 0xFFFF0000.toInt()),
    ),
    denseVoxelM = 0.03f,
)
val bytes = RerunRrdWriter.write(
    scene, FakeRerunImageCodec,
    recordingId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000021"),
)
```

This writes world coordinates and one static dense chunk, without sparse points or poses.
An iOS test should read `Fixtures/android-dense-only.rrd` relative to `#filePath`
and use the assertions in `testSingleDenseChunkLoadsAPackAndPersistsAsV2`.
For a bundled resource instead, add an explicit PBX resource entry: this project does
not automatically include files. The new tests are in the already registered
`RerunRRDReaderTests.swift` (PBX file ref, group, build file and test Sources entry).
There is no existing skipped-test pattern in the RRD tests; none is added here.

Remaining visual interoperability proof: open this Android dense-only RRD in the iOS
simulator; open an iOS dense export in the Android demo and the Rerun viewer.
The iOS stage currently carries `session.pack.dense` but does not draw this layer.
