<!-- category: Fixed -->
- **ReflectionProbeNode** no longer crashes when it leaves the composition after the scene's environment was swapped (a dark/light toggle): it gave Filament back an `IndirectLight` that environment had already destroyed. It now restores the previous light only while the scene still holds its own, and only if that light is still alive. (#4924)
