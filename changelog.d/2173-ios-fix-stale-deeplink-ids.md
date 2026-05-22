<!-- category: Fixed -->
- **iOS registry: remove stale `ar-eis` / `ar-pose-placement` deep-link aliases** — the canonical Android IDs (`ar-image-stabilization`, `ar-pose`) were already present in `allowedIds`; the aliases were unreachable duplicates that silently dropped `sceneview://demo/ar-image-stabilization` QR-code taps. (#2173)
