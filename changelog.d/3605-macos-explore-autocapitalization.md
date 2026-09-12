<!-- category: Fixed -->
- **The Explore tab's search field no longer breaks the macOS demo build ([#3605](https://github.com/sceneview/sceneview/issues/3605)).** `textInputAutocapitalization` is iOS-only and is now behind an `#if os(iOS)` guard.
