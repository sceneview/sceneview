package io.github.erkko68.filament.gltfio

import io.github.erkko68.filament.ensureFilamentLoaded

actual object Gltfio {
    actual fun init() {
        // gltfio lives in the same combined libfilament-c image as filament; loading it once
        // (idempotent) is all that's needed — there is no separate gltfio init entry point.
        ensureFilamentLoaded()
    }
}
