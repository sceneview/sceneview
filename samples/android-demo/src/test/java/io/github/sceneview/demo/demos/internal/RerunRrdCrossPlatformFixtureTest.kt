package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The `.rrd` both demo apps must write byte for byte: this writer's output for one small scene is
 * committed in the iOS tests (`samples/ios-demo/SceneViewDemoTests/Fixtures/android-cross-platform.rrd`),
 * where the iOS writer is checked against it and the iOS reader reads it. The scene is the one
 * `RerunRRDWriterTests.crossPlatformScene()` builds in Swift; its one photo is a real PNG, so
 * [FakeRerunImageCodec] and ImageIO both pass it through at its own 480 × 640.
 *
 * Delete the fixture (or set `RERUN_FIXTURE_WRITE=1`) to regenerate it after a deliberate format
 * change, then rerun the iOS `RerunRRDWriterTests`.
 */
class RerunRrdCrossPlatformFixtureTest {
    private val fixtures = listOf(
        File("../ios-demo/SceneViewDemoTests/Fixtures"),
        File("samples/ios-demo/SceneViewDemoTests/Fixtures"),
    ).first { it.isDirectory }

    private fun scene(): RerunExportScene {
        val identity = RerunExportScene.Quat.Identity
        val turned = RerunExportScene.Quat(0f, 0.6f, 0f, 0.8f)
        val photoPose = RerunExportScene.CameraSample(0.75, Vec3(0.25f, 1.5f, -0.5f), turned)
        return RerunExportScene(
            title = "Cross-platform room",
            lens = RerunExportScene.Lens(960, 1280, 1000f, 1001f, 480f, 640f),
            points = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f)),
            pointColors = listOf(
                RerunExportScene.Rgb(255, 0, 0),
                RerunExportScene.Rgb(0, 255, 0),
                RerunExportScene.Rgb(0, 0, 255),
            ),
            cameraPath = listOf(
                RerunExportScene.CameraSample(0.0, Vec3(0f, 1.5f, 0f), identity),
                RerunExportScene.CameraSample(0.5, Vec3(0.125f, 1.5f, -0.25f), identity),
                RerunExportScene.CameraSample(1.0, Vec3(0.5f, 1.5f, -1f), turned),
            ),
            keyframes = emptyList(),
            images = mapOf("frames/000.png" to File(fixtures, "cross-platform-photo.png").readBytes()),
            planes = listOf(
                RerunExportScene.Plane(
                    1,
                    "horizontal_upward",
                    listOf(Vec3(-1f, 0f, -1f), Vec3(1f, 0f, -1f), Vec3(1f, 0f, 1f), Vec3(-1f, 0f, 1f)),
                    texture = null,
                ),
            ),
            anchors = listOf(RerunExportScene.Anchor(7, Vec3(0.5f, 0f, -0.5f), identity, "shiba")),
            photos = listOf(RerunExportScene.Keyframe(0.75, "frames/000.png", photoPose)),
            pointObservations = listOf(
                RerunExportScene.PointObservation(1.25, listOf(1, 2)),
                RerunExportScene.PointObservation(0.5, listOf(0)),
            ),
            dense = DenseCloud(
                floatArrayOf(0f, 0f, 0f, 1f, 2f, 3f),
                intArrayOf(0xFF0A141E.toInt(), 0xFFFF0000.toInt()),
            ),
            denseVoxelM = 0.03f,
        )
    }

    @Test
    fun `the committed cross-platform fixture is what this writer writes`() {
        val written = RerunRrdWriter.write(
            scene(),
            FakeRerunImageCodec,
            recordingId = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301"),
        )
        val fixture = File(fixtures, "android-cross-platform.rrd")
        if (!fixture.exists() || System.getenv("RERUN_FIXTURE_WRITE") == "1") fixture.writeBytes(written)
        assertArrayEquals(fixture.readBytes(), written)
    }
}
