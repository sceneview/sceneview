#!/usr/bin/env python3
"""Pin recovery guidance and existing visibility gates without an ARCore device."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
LIBRARY = "arsceneview/src/main/java/io/github/sceneview/ar/"
DEMO = "samples/android-demo/src/main/java/io/github/sceneview/demo/"
CALLS = {
    LIBRARY + "AutoPlacementScene.kt":
        "if (availability == null && !cameraDenied) guidance.cue else ArGuidanceCue.NONE",
    LIBRARY + "PlacementScene.kt":
        "if (cameraDenied || arCoreUnavailable) ArGuidanceCue.NONE else guidance.cue",
    DEMO + "common/placement/TapToPlaceArSession.kt": "coachingCue",
    DEMO + "common/placement/ARFeatureComparison.kt":
        "if (availability == null && !startupTimedOut) guidance.cue else ArGuidanceCue.NONE",
    DEMO + "demos/ARPlaneNodeDemo.kt":
        "if (arCoreAvailability == null) guidance.cue else ArGuidanceCue.NONE",
    DEMO + "demos/ARSurfacesDemo.kt":
        "if (arCoreAvailability == null) guidance.cue else ArGuidanceCue.NONE",
    DEMO + "demos/ARMeasureDemo.kt":
        "if (arCoreAvailability == null && !arSessionFailed) guidance.cue else ArGuidanceCue.NONE",
    DEMO + "demos/ARPoolDemo.kt":
        "if (availability == null && !startupTimedOut) guidance.cue else ArGuidanceCue.NONE",
}


def overlay_calls(source):
    # Ignore comments and strings so parentheses in them cannot terminate a call.
    source = re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"', "", source, flags=re.S)
    calls = []
    for match in re.finditer(r"\bARCoachingOverlay\s*\(", source):
        depth = 1
        end = match.end()
        while depth and end < len(source):
            depth += (source[end] == "(") - (source[end] == ")")
            end += 1
        if depth:
            raise AssertionError("Unterminated ARCoachingOverlay call")
        calls.append(source[match.end():end - 1])
    return calls


class CoachingWiringTest(unittest.TestCase):
    def test_integrated_calls_forward_guidance_and_preserve_visibility(self):
        for path, cue in CALLS.items():
            with self.subTest(path=path):
                source = (ROOT / path).read_text()
                self.assertRegex(
                    source,
                    re.compile(
                        r"\bval\s+guidance\s*=\s*rememberArGuidanceState\([^)]*\btrackingFailureReason\s*=",
                        re.S,
                    ),
                )
                calls = overlay_calls(source)
                self.assertEqual(1, len(calls), "Review new coaching call sites explicitly")
                call = calls[0]
                self.assertRegex(call, r"\bguidance\s*=\s*guidance\b")
                compact = re.sub(r"\s+", "", call)
                self.assertIn("cue=" + re.sub(r"\s+", "", cue) + ",", compact)
                if cue == "coachingCue":
                    self.assertIn(
                        "valcoachingCue=if(state.arCoreAvailability==null&&!state.motionSensorSilent)"
                        "{guidance.cue}else{ArGuidanceCue.NONE}",
                        re.sub(r"\s+", "", source),
                    )


if __name__ == "__main__":
    unittest.main()
