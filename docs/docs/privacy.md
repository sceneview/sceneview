---
title: Privacy Policy — SceneView Demo
description: "Privacy policy for the SceneView demo app: offline by default, no tracking, and an on-device, share-based bug reporter (no recording, no upload)."
---

# Privacy Policy

**Last updated:** July 5, 2026

## SceneView Demo App

The SceneView Demo app ("the App") is an open-source demonstration application developed by the SceneView project.

## Summary

The App **collects no personal data**. It has no accounts, no analytics, and no tracking. The App never records your screen or microphone and never uploads anything by itself. The **optional in-app bug reporter** assembles a report (device info, the App's own recent log lines, and an optional screenshot of the App) **on your device only** — it is sent exclusively through an app *you* pick in the Android share sheet, or as a pre-filled GitHub issue that you review and submit yourself in the browser.


## Face Data (TrueDepth API / ARKit Face Tracking)

- **What is collected:** when you open an AR face demo on iOS, the app uses Apple's ARKit face tracking (which relies on the TrueDepth camera) to obtain a real-time facial mesh — face geometry and expression coefficients. On Android, the equivalent demo uses Google ARCore Augmented Faces. No photographs of your face are taken by the app.
- **Purpose:** face data is used **solely** to render real-time 3D effects anchored to your face inside the demo, on screen, while the demo is open. It is not used for identification, authentication, profiling, or any other purpose.
- **Storage & retention:** face data is processed **in memory, on your device only**. It is never recorded, stored, written to disk, or retained — it is discarded as each camera frame is rendered, and entirely released when you leave the demo.
- **Sharing:** face data **never leaves your device**. It is not transmitted to us or to any third party, and the app contains no analytics or tracking SDKs.

## Data Collection (normal use)

Outside the feedback feature, the App does **not** collect, store, or transmit any personal data. Specifically:

- No user accounts or registration required
- No analytics or tracking SDKs
- No advertising frameworks
- No personal information collected

## Camera and AR

The App uses your device camera solely for Augmented Reality (AR) features powered by ARCore (Android) and ARKit (iOS). Camera data for AR is processed entirely on-device and is never recorded, stored, or transmitted.

An optional bug-report **screenshot** (see below) captures the App's own window — including the AR camera viewfinder if an AR demo is open — a single still image, taken only when you open the bug reporter, previewed in the sheet, and excludable with one toggle.

## Network Usage

The App may download 3D model files (`.glb`, `.gltf`, `.usdz`) and HDR environments from the internet for demonstration purposes. No personal data is sent during these requests. The in-app bug reporter itself uses no network — it hands the report to an app you pick.

## In-App Bug Reports (opt-in)

The App includes a **feedback button** that opens a lightweight "Report a bug" sheet. It uses **no runtime permission, no screen or audio recording, and no background service**, and the App **never uploads anything itself**.

### What the report contains

| Data | Detail |
|------|--------|
| Screenshot (optional) | A single still image of the App's own window, captured with Android's `PixelCopy` — only this App's content, never other apps or notifications. Previewed in the sheet with an include/exclude toggle. |
| App log | The tail of the App's **own** log lines (Android lets an app read only its own log — no `READ_LOGS` permission involved). |
| Typed text | Any optional description you type. |
| Device & app context | App version and build number, Android version and API level, device manufacturer and model, ABI, screen size, and locale. No advertising ID, no IP address, no account identifier. |

### Where it goes — you decide

The report never leaves the device on its own. You choose one of two exits:

1. **Share report** — the report opens in the standard Android share sheet and is sent through whatever app you pick (email, GitHub, a messenger, …). From there the data is handled by the app you chose, under its own privacy policy.
2. **Open a GitHub issue** — a pre-filled public issue form opens in your browser; nothing is posted until you review and submit it there. This path carries text only (a URL cannot include the screenshot).

If you dismiss the sheet, the report is discarded. The temporary screenshot file lives in the App's private cache and is cleaned up automatically.

!!! note "Earlier app versions (≤ 4.18.x)"
    Older releases shipped a consent-gated screen + voice recorder that uploaded to the SceneView feedback service (an open-source Cloudflare Worker), where recordings were stored privately and auto-deleted after 90 days. That system has been removed from the App; the retention and deletion commitments continue to apply to any recording submitted by an older version until it is deleted.

## Third-Party Services

The App uses the following platform services:

- **ARCore** (Android): Google's AR platform. Subject to [Google's Privacy Policy](https://policies.google.com/privacy).
- **ARKit** (iOS): Apple's AR framework. Subject to [Apple's Privacy Policy](https://www.apple.com/privacy/).
- **GitHub** (bug reports only, when you choose that path): hosts the public issue you submit. Subject to [GitHub's Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).

## Data Sharing

- The only data that ever leaves the device is a bug report **you** choose to send — through a share-sheet app you pick, or as a public GitHub issue you review and submit yourself. Nothing is sold or used for advertising.

## Open Source

The App is fully open source. You can review the complete source code — including the bug reporter — at [github.com/sceneview/sceneview](https://github.com/sceneview/sceneview).

## Contact

For privacy questions, open an issue on our [GitHub repository](https://github.com/sceneview/sceneview/issues).
