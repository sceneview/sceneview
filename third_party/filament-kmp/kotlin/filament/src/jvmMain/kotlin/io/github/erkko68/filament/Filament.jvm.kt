package io.github.erkko68.filament

actual object Filament {
    actual fun init() {
        // Extract and System.load the combined libfilament-c so jextract's loaderLookup resolves.
        ensureFilamentLoaded()
    }
}
