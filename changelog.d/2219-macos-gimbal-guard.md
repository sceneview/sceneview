<!-- category: Fixed -->
- Fix macOS archive failure: `CameraControls.gimbal` is iOS-only — guard with `#elseif os(macOS)` and fall back to orbit gesture path on macOS (#2219 follow-up).
