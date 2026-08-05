package io.github.erkko68.filament

/**
 * Filament is the main module initialization singleton.
 *
 * The Filament object provides module-level initialization and setup. Most users do not need
 * to interact with this directly; the Engine initialization handles necessary setup automatically.
 * However, on some platforms, calling init() may be required for proper functionality.
 *
 * @see Engine
 */
expect object Filament {
    /**
     * Initializes the Filament module.
     *
     * This is typically called automatically during Engine initialization, but may need to be
     * called explicitly on some platforms (e.g., Android) for proper module setup.
     */
    fun init()
}
