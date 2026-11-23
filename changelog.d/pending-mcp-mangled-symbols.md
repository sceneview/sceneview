<!-- category: Fixed -->
- **`validate_code` no longer rejects `PlacementScene`, `FogNode` and other composables with a `Dp` or `Color` parameter.** Their JVM names are mangled in the `.api` dumps (`PlacementScene-O_N-pM8`), and the MCP symbol index skipped them, so documented code was reported as `symbols/unknown-import`. The index now registers them under their Kotlin name.
