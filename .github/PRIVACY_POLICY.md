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

When you submit feedback:

- The app records **your screen**, and — if you allow it — **your microphone**,
  so you can demonstrate and describe the issue.
- The screen recording, a transcript of your narration, and basic device
  context (app version, Android version, device model, locale, free memory)
  are sent over HTTPS to the SceneView feedback service.
- A pre-filled issue is opened in the public SceneView issue tracker on GitHub.
  **That public issue contains only the written transcript and the device
  context — never your screen recording or audio.**
- The screen recording and audio are kept **private** and are **automatically
  deleted after 90 days**; only the SceneView maintainers can view them.

You can cancel at any point before sending. If you never use the feedback
feature, none of the above applies.

### Third-Party SDKs

| SDK | Purpose | Data collected |
|-----|---------|---------------|
| Google Filament | 3D rendering engine | None |
| Google ARCore | Augmented reality | Camera processed locally, no data sent to Google |
| Jetpack Compose | UI framework | None |

### Data Sharing

- The only data that ever leaves the device is feedback **you** choose to
  submit. It is sent to the SceneView feedback service (a Cloudflare Worker
  operated by the SceneView open-source project) and surfaces as an issue on
  GitHub, as described in *In-App Feedback* above.
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
