# Setting up ARCore Geospatial / Streetscape Geometry / Cloud Anchors

The android-demo `Streetscape Geometry`, `Cloud Anchors`, and any future Geospatial-based demo require an **ARCore Cloud API key** wired through the AndroidManifest. Without one, those specific demos disable themselves at runtime and surface a clear status message — the rest of the app (Face Mesh, AR Placement, Pose, plain camera AR, all 3D demos) keeps working.

## Why this is gated

Google's ARCore Geospatial / Streetscape / Cloud Anchors APIs hit a hosted backend. They:
- require an ARCore API key bound to your Android package + signing certificate
- require billing enabled on the Google Cloud project
- only return useful data in areas covered by Google Street View VPS

For maintainers / contributors who want to actually exercise the demo, the steps below take ~5 minutes.

## Provisioning your own key

### 1. Enable the ARCore API on a Cloud project

```
https://console.cloud.google.com/apis/library/arcore.googleapis.com
```

Pick (or create) a project, click **Enable**. ARCore API replaces the deprecated "ARCore Cloud Anchor API (Legacy)" — that one is end-of-life, do **not** enable it.

### 2. Activate billing on the project (required by Geospatial)

Geospatial mode hits paid endpoints (Google Cloud documents this explicitly). Without billing, `Session.configure(GeospatialMode.ENABLED)` succeeds but the backend returns no Streetscape geometries.

A defensive budget alert at €1/month is recommended:
```
https://console.cloud.google.com/billing/budgets
```

The free tier on ARCore is generous (thousands of calls/day for a single test device). Real-world risk for a developer testing locally is in the order of a few cents.

### 3. Create an API key restricted to this app

```
https://console.cloud.google.com/apis/credentials
→ Create credentials → API key
```

Restrict the key:
- **Application restrictions** → Android apps
  - Package name: `io.github.sceneview.demo`
  - SHA-1 fingerprint: your debug keystore's SHA-1 (`keytool -list -v -keystore ~/.android/debug.keystore -storepass android -alias androiddebugkey`) and/or your Play App Signing SHA-1 from Play Console.
- **API restrictions** → restrict key → ARCore API only.

#### Play App Signing key (production blocker — issue #1177)

When Google Play **App Signing** is enabled (default on the SceneView demo), the upload bundle is re-signed by Google before it hits user devices. The on-device APK is signed with the **App Signing key**, NOT the upload key — so the upload key SHA-1 doesn't match anything at runtime.

To unblock Cloud Anchors / Geospatial / Streetscape on Play Store production builds:

1. **Play Console** → your app → **Setup** → **App integrity** → **App signing**.
2. Copy the **App signing key certificate SHA-1** (the one labelled "App signing key", not "Upload key").
3. **Google Cloud Console** → **APIs & Services** → **Credentials** → click the ARCore API key.
4. Under **Application restrictions** → **Android apps** → **+ ADD AN ITEM**:
   - Package name: `io.github.sceneview.demo`
   - SHA-1: paste the App signing key SHA-1 from step 2.
5. Save. Propagation is ~1 minute. No new APK/AAB cut needed — the running production build will start authorizing immediately.

Symptom when this is missing: Cloud Anchors `Host` / `Resolve` returns `ERROR_NOT_AUTHORIZED`; Terrain / Rooftop / Streetscape demos report "API key not authorized". The in-app status banner surfaces this specific case so users see actionable guidance instead of a raw enum.

If you rotate the App Signing key (Play Console → Use Play App Signing → rotate), the SHA-1 changes — re-do steps 2–5.

#### Troubleshooting — `ERROR_NOT_AUTHORIZED` persists after SHA-1 is whitelisted

If the App Signing key SHA-1 IS in the Cloud Console "Application restrictions" list but `Host` / `Resolve` still returns `ERROR_NOT_AUTHORIZED` on production, walk this checklist in order. Each step has a direct deep-link — replace `<PROJECT_ID>` with the Cloud project that owns the ARCore API key (find it at the top-right of the Cloud Console; for the SceneView demo it's `pc-api-4638313286439917620-648`).

1. **Billing is enabled and active** on the Cloud project. Geospatial / Cloud Anchors / Streetscape **all hit paid backends** and silently return `ERROR_NOT_AUTHORIZED` if billing is missing, disabled, or in a free-tier-exhausted state.
   - Check: https://console.cloud.google.com/billing/linkedaccount?project=<PROJECT_ID>
   - Must show "Billing is enabled" with a linked account in good standing.

2. **"ARCore API" is enabled** on the project (not the legacy "ARCore Cloud Anchor API").
   - Check: https://console.cloud.google.com/apis/api/arcore.googleapis.com/overview?project=<PROJECT_ID>
   - Must show "API enabled" with a recent traffic graph. If you see "Cloud Anchor API (Legacy)" enabled instead, disable it and enable "ARCore API" — they are different products.

3. **API restrictions on the key** (this is a SEPARATE section from "Application restrictions"). Open the key edit page and scroll past Application restrictions:
   - If "API restrictions" is set to **"Restrict key"**, the explicit allow-list MUST contain **"ARCore API"** by name. If only "Cloud Anchor API (Legacy)" is listed, every call to the modern Geospatial / Cloud Anchor endpoints returns `ERROR_NOT_AUTHORIZED`.
   - The safest setting is **"Don't restrict key"** — Application restrictions (Android package + SHA-1) already gate the key to your APK, so API restrictions are belt-and-braces. If you keep "Restrict key", verify the list every time you enable a new ARCore feature.

4. **Propagation delay**. Google says ~1 minute but in practice we've observed `ERROR_NOT_AUTHORIZED` for 5-15 minutes after a fresh SHA-1 addition. Force-stop the app and retry; if still failing after 30 minutes, the cause is one of 1-3 above.

5. **Multiple Cloud projects**. If your account has multiple projects, double-check that the API key you whitelisted the SHA-1 on is the SAME key whose value is in the `ARCORE_API_KEY` env var / GitHub secret. A common pitfall: the runbook is followed against project A while the GitHub secret holds a key from project B (which has no SHA-1 whitelist).

If all five pass and the error persists, capture the failing device's `adb logcat | grep -i arcore` output during a `Host` attempt — the ARCore client library logs the HTTP status from Google's auth backend and the exact request URL, which pinpoints whether the rejection is on the key itself or on a downstream service.

### 4. Wire the key locally

Append to `local.properties` at the repo root (this file is gitignored):

```properties
ARCORE_API_KEY=AIzaSy...your-key-here...
```

That's it. `samples/android-demo/build.gradle` reads `ARCORE_API_KEY` from either:
1. `ARCORE_API_KEY` environment variable
2. `local.properties` → `ARCORE_API_KEY`
3. empty fallback

…and injects it into the `<meta-data android:name="com.google.android.ar.API_KEY">` placeholder in the manifest at build time.

### 5. (Optional) Wire the key in CI

For SceneView maintainers, the GitHub Actions secret `ARCORE_API_KEY` is read by the workflows that build the sample app:

- `play-store.yml` (release AABs uploaded to Play Store)
- `build-apks.yml`, `ci.yml`, `pr-check.yml`, `quality-gate.yml`, `render-tests.yml`

Forks and PRs from forks won't have the secret — the demo's runtime check disables Geospatial gracefully and the build still succeeds.

## What "no Streetscape geometry visible" means

Even with everything wired correctly, you'll see no overlay if any of these fail:

- **No Street View VPS coverage in your area.** Strasbourg city centre / Paris / NYC / Tokyo work; suburbs and rural areas often don't.
- **Indoor.** Geospatial needs a clear-ish sky view to compute its localization.
- **Tracking still initializing.** The first 5-30 seconds after launch the device is calibrating its IMU; the banner status will say "Looking for streetscape geometry…" or "Initializing geospatial…".

The status banner at the bottom of the demo distinguishes these cases — read it before assuming the integration is broken.

## Permissions

The demo asks for both `CAMERA` and `ACCESS_FINE_LOCATION` at runtime, gated through a single `RequestMultiplePermissions` flow. ACCESS_FINE_LOCATION is mandatory — `Session.configure(GeospatialMode.ENABLED)` throws `FineLocationPermissionNotGrantedException` otherwise. The composable holds a "Requesting permissions…" UI until both grants resolve.
