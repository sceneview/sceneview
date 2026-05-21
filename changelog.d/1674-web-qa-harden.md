<!-- category: Tests -->
- Web device-QA WebXR coverage now drives a full `immersive-ar` / `immersive-vr` session against the IWER emulated device — requests the session, runs the XR animation frame loop, nudges pose/controllers and ends it — replacing the fixture-pending soft-skip, so a WebXR-plumbing regression fails the suite instead of silently skipping (#1674, #1748).
