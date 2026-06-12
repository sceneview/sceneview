# Privacy Policy — SceneView Demo

**Last updated:** May 21, 2026

## Summary

**SceneView Demo collects no personal data and its 3D content works fully
offline.** The one exception is the **optional in-app feedback feature**: if you
choose to report a bug or share an idea, it records your screen and microphone
and sends them to the SceneView team. Nothing is recorded or sent unless you
explicitly start a feedback report and agree.

## Details

### Data Collection

- **No personal data collected** — The app does not collect names, email addresses, IP addresses, or any other personally identifiable information.
- **No analytics or tracking** — No analytics SDKs, telemetry, fingerprinting, or behavioral tracking of any kind.
- **Offline by default** — All 3D models, textures, and environments are bundled locally and need no network. The only feature that uses the network is the opt-in feedback reporter described below.
- **No accounts** — The app does not require or support user accounts.

### Camera Usage (AR Features)

- The app uses the device camera **exclusively** for augmented reality features powered by Google ARCore.
- Camera data is processed **locally on-device** in real time for plane detection and object placement.
- **No camera images or video are stored, recorded, or transmitted** to any server.
- Camera access is only requested when the user navigates to the AR tab.

### In-App Feedback (Optional)

The app includes an optional feedback reporter so you can report a bug or
suggest an idea. It is **opt-in and consent-gated** — nothing is recorded or
sent unless you start a feedback report and explicitly agree on the consent
screen.

**What is captured.** When you submit feedback:

- The app records **your screen** (a short `.mp4` — it may include the AR
  camera viewfinder if an AR demo is open) and, if you allow it, **your
  microphone audio**, so you can demonstrate and describe the issue.
- It also collects **device/app context**: app version and build number,
  Android version and API level, device manufacturer and model, locale, and
  free RAM. No advertising ID, no IP address, no account identifier.
- For a bug report a screen recording is encouraged; for an idea, a quick
  voice note or plain text is enough — a recording is never forced.

**Where it goes.** The recording, audio, and context are uploaded over HTTPS
to the **SceneView feedback service** — an open-source Cloudflare Worker
operated by the SceneView project
([`feedback-worker/`](https://github.com/sceneview/sceneview/tree/main/feedback-worker)).
The service then:

1. Stores the screen recording and audio **privately** in **Cloudflare R2**
   object storage — a private bucket, not publicly listable or indexable.
2. Transcribes your voice audio to text using **Cloudflare Workers AI** (the
   OpenAI **Whisper** model). The audio is processed for transcription only and
   is not used to train any model.
3. Opens a pre-filled issue in the public SceneView issue tracker on GitHub.
   **That public issue contains only the written transcript, any text you
   typed, and the device/app context — never your screen recording or audio.**
   Please avoid showing or saying personal information while recording, since
   the transcript and context are public.

**Access control & retention.** The screen recording and audio are accessible
only to SceneView maintainers, through an **admin-token-gated viewer**. Without
that token the viewer page shows only the public transcript and context — the
media players are not available; media is served with `private, no-store`
caching and the viewer page is `noindex`. The recording and audio are
**automatically deleted after 90 days** by a scheduled job; the transcript and
context (already public on the GitHub issue) are kept. The service hashes the
request IP for rate limiting only — the raw IP is never stored.

You can cancel at any point before sending. If you never use the feedback
feature, none of the above applies.

### Third-Party SDKs

| SDK | Purpose | Data collected |
|-----|---------|---------------|
| Google Filament | 3D rendering engine | None |
| Google ARCore | Augmented reality | Camera processed locally, no data sent to Google |
| Jetpack Compose | UI framework | None |
| Android MediaProjection / MediaRecorder | Screen + audio capture for the opt-in feedback feature | Only when you record feedback (see *In-App Feedback*) |

### Third-Party Processors (feedback feature only)

These processors handle data **only** when you choose to submit feedback:

| Service | Role | Data handled |
|---------|------|--------------|
| Cloudflare R2 | Private object storage | Screen recording, audio |
| Cloudflare Workers AI (Whisper) | Speech-to-text transcription | Audio |
| GitHub | Public issue tracker | Transcript, typed text, device/app context |

### Data Sharing

- The only data that ever leaves the device is feedback **you** choose to
  submit. It is sent to the SceneView feedback service (a Cloudflare Worker
  operated by the SceneView open-source project), processed by the services in
  the *Third-Party Processors* table above, and surfaces as an issue on GitHub,
  as described in *In-App Feedback* above.
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
