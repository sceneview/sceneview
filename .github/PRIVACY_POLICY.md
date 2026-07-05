# Privacy Policy — SceneView Demo

**Last updated:** July 5, 2026

## Summary

**SceneView Demo collects no personal data and its 3D content works fully
offline.** The app never records your screen or microphone and never uploads
anything by itself. The **optional in-app bug reporter** assembles a report
(device info, the app's own recent log, and an optional screenshot of the app)
**on your device only** — it is sent exclusively through an app *you* pick in
the Android share sheet, or as a pre-filled GitHub issue you review before
submitting.

## Details

### Data Collection

- **No personal data collected** — The app does not collect names, email addresses, IP addresses, or any other personally identifiable information.
- **No analytics or tracking** — No analytics SDKs, telemetry, fingerprinting, or behavioral tracking of any kind.
- **Offline by default** — All 3D models, textures, and environments are bundled locally and need no network. The in-app bug reporter is offline too: it composes the report on-device and hands it to the Android share sheet (or your browser) — the app itself uploads nothing.
- **No accounts** — The app does not require or support user accounts.

### Camera Usage (AR Features)

- The app uses the device camera **exclusively** for augmented reality features powered by Google ARCore.
- Camera data is processed **locally on-device** in real time for plane detection and object placement.
- **No camera images or video are stored, recorded, or transmitted** to any server.
- Camera access is only requested when the user navigates to the AR tab.

### In-App Bug Reports (Optional)

The app includes an optional, lightweight bug reporter. It uses **no runtime
permission, no screen recording, no microphone, and no background service**,
and the app **never uploads anything itself**.

**What the report contains.** When you open "Report a bug":

- an optional **screenshot of the app's own window** (captured via Android's
  `PixelCopy` — only this app's content, never other apps or notifications),
  with an in-sheet preview and an include/exclude toggle;
- the **app's own recent log lines** (Android lets an app read only its own
  log — no `READ_LOGS` permission is involved);
- **device/app context**: app version and build number, Android version and
  API level, device manufacturer and model, ABI, screen size, and locale. No
  advertising ID, no IP address, no account identifier;
- any **description you type**.

**Where it goes — you decide.** The report never leaves the device on its
own. You choose one of two exits:

1. **Share** — the report opens in the standard Android share sheet and is
   sent through whatever app you pick (email, GitHub, a messenger, …). The
   data is then handled by that app under its own privacy policy.
2. **Open a GitHub issue** — a pre-filled public issue form opens in your
   browser; nothing is posted until you review and submit it there. This path
   carries text only (the screenshot cannot ride in a URL).

If you dismiss the sheet, the report is discarded; the temporary screenshot
file lives in the app's private cache and is cleaned up automatically.

> **Earlier app versions (≤ 4.18.x)** shipped a consent-gated screen + voice
> recorder that uploaded to the SceneView feedback service (a Cloudflare
> Worker), where recordings were stored privately and auto-deleted after 90
> days. That system has been removed from the app; the retention and deletion
> commitments above continue to apply to any recording submitted by an older
> version until it is deleted.

### Third-Party SDKs

| SDK | Purpose | Data collected |
|-----|---------|---------------|
| Google Filament | 3D rendering engine | None |
| Google ARCore | Augmented reality | Camera processed locally, no data sent to Google |
| Jetpack Compose | UI framework | None |


### Data Sharing

- The only data that ever leaves the device is a bug report **you** choose to
  send — through a share-sheet app you pick, or as a GitHub issue you review
  and submit yourself, as described in *In-App Bug Reports* above.
- No data is sold or used for advertising.

### Children's Privacy

- The app does not knowingly collect any data from children under 13.
- The app is rated for ages 13 and older.

### Changes to This Policy

We may update this Privacy Policy from time to time. Changes will be posted in this file with an updated date.

### Contact

If you have questions about this Privacy Policy, please contact:

- **Email:** thomas.gorisse@gmail.com
- **Website:** https://sceneview.github.io
- **GitHub:** https://github.com/sceneview/sceneview

## Face Data (TrueDepth API / ARKit Face Tracking)

- **What is collected:** when you open an AR face demo on iOS, the app uses Apple's ARKit face tracking (which relies on the TrueDepth camera) to obtain a real-time facial mesh — face geometry and expression coefficients. On Android, the equivalent demo uses Google ARCore Augmented Faces. No photographs of your face are taken by the app.
- **Purpose:** face data is used **solely** to render real-time 3D effects anchored to your face inside the demo, on screen, while the demo is open. It is not used for identification, authentication, profiling, or any other purpose.
- **Storage & retention:** face data is processed **in memory, on your device only**. It is never recorded, stored, written to disk, or retained — it is discarded as each camera frame is rendered, and entirely released when you leave the demo.
- **Sharing:** face data **never leaves your device**. It is not transmitted to us or to any third party, and the app contains no analytics or tracking SDKs.
