<!-- category: Fixed -->
- **Sketchfab (Android demo):** stop the 5+ second ANR when opening the model preview sheet. The Filament `Engine` is now allocated inside the Rendering stage instead of at the sheet root, so the synchronous JNI cost no longer blocks the main thread on the user's tap that opens the sheet — Preview + Downloading stages don't need Filament at all. (#2193)
