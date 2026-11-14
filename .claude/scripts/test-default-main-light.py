#!/usr/bin/env python3
"""Pin default key-light direction across platforms without a rendering device.

Run from any directory: python3 .claude/scripts/test-default-main-light.py
RealityKit aims its local -Z at the target, so aiming at the origin from a given
position produces a travel direction equal to the negated position. Filament
accepts the travel direction directly. Compare normalised vectors so equivalent
directions pass regardless of magnitude.
"""

import math
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
WEB = Path("sceneview-web/src/jsMain/kotlin/io/github/sceneview/web")
SWIFT = Path("SceneViewSwift/Sources/SceneViewSwift")


def source(path):
    return (ROOT / path).read_text()


def vector(text):
    return tuple(float(value.strip().removesuffix("f")) for value in text.split(","))


def normalised(values):
    length = math.sqrt(sum(value * value for value in values))
    return tuple(value / length for value in values)


class DefaultMainLightTests(unittest.TestCase):
    def assertDirectionMatchesAndroid(self, direction):
        android = source(Path("sceneview/src/main/java/io/github/sceneview/SceneFactories.kt"))
        expected = vector(re.search(
            r"val DEFAULT_MAIN_LIGHT_DIRECTION = Direction\(([^)]+)\)", android
        ).group(1))
        self.assertEqual(len(direction), 3)
        self.assertEqual(len(expected), 3)
        for actual, wanted in zip(normalised(direction), normalised(expected)):
            self.assertAlmostEqual(actual, wanted, places=6)

    def assertSwiftMainLight(self, filename, provision_method):
        provision = source(SWIFT / filename).split("private func " + provision_method, 1)[1]
        main = provision.split("case .main:", 1)[1].split("case .fill:", 1)[0]
        position = vector(re.search(
            r"main.entity.look\(at: \.zero, from: \[([^]]+)\], relativeTo: nil\)", main
        ).group(1))
        self.assertDirectionMatchesAndroid(tuple(-value for value in position))
        self.assertIn("castsShadow: true", main)

    def test_swift_3d_main_light(self):
        self.assertSwiftMainLight("SceneView.swift", "provisionLightSlot(")

    def test_swift_ar_main_light(self):
        self.assertSwiftMainLight("ARSceneView.swift", "provisionARLightSlot(")

    def test_web_dsl_main_light(self):
        key = source(WEB / "SceneView.kt").split("val keyLight = LightConfig().apply {", 1)[1]
        key = key.split("}", 1)[0]
        self.assertDirectionMatchesAndroid(vector(re.search(r"direction\(([^)]+)\)", key).group(1)))

    def test_web_javascript_viewer_main_light(self):
        key = source(WEB / "Main.kt").split("light {", 1)[1].split("}", 1)[0]
        self.assertDirectionMatchesAndroid(vector(re.search(r"direction\(([^)]+)\)", key).group(1)))


if __name__ == "__main__":
    unittest.main(verbosity=2)
