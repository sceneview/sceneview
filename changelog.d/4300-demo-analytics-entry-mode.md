<!-- category: Changed -->
- **Demo apps (Android and iOS): `sample_open` now sends the category as a stable slug (`create`, `view_3d`, `place_ar`, `understand`, `dev_tools`) plus the received `entry_id` and the umbrella card's `mode`; each mode change inside an umbrella card logs `sample_interaction` with `control = "mode_<x>"`.** Demo-app instrumentation only; the SDKs still ship no telemetry.
