<!-- category: Changed -->
- CI: the unified-demo render goldens leg runs its emulator on `swangle_indirect`, which survives a live Filament viewport where `swiftshader_indirect` lost the emulator, and compares 13 of the 15 demos against goldens recorded and reviewed on that profile (`render-goldens-swangle/`). The test now captures only once the demo reports "Scene ready" (#3554).
