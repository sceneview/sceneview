<!-- category: Fixed -->
The Explore tab's search field no longer breaks the macOS demo build: `textInputAutocapitalization` is iOS-only and is now behind an `#if os(iOS)` guard.
