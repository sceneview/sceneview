---
title: Privacy Policy — SceneView Demo
description: "Privacy policy for the SceneView demo app: offline by default, no tracking, and the opt-in in-app feedback feature (screen + audio recording)."
---

# Privacy Policy

**Last updated:** May 21, 2026

## SceneView Demo App

The SceneView Demo app ("the App") is an open-source demonstration application developed by the SceneView project.

## Summary

The App **collects no personal data during normal use**. It has no accounts, no analytics, and no tracking. The **one exception** is the **optional in-app feedback** feature: if you choose to report a bug or share an idea, it records your screen and microphone audio and uploads them — together with basic device/app context — to the SceneView feedback service. Nothing is recorded or sent unless you explicitly start a feedback report and agree.

## Data Collection (normal use)

Outside the feedback feature, the App does **not** collect, store, or transmit any personal data. Specifically:

- No user accounts or registration required
- No analytics or tracking SDKs
- No advertising frameworks
- No personal information collected

## Camera and AR

The App uses your device camera solely for Augmented Reality (AR) features powered by ARCore (Android) and ARKit (iOS). Camera data for AR is processed entirely on-device and is never recorded, stored, or transmitted.

A feedback **screen recording** (see below) does capture whatever is on screen — including the AR camera viewfinder if an AR demo is open — but only while you are actively recording feedback and only after you grant screen-capture consent.

## Network Usage

The App may download 3D model files (`.glb`, `.gltf`, `.usdz`) and HDR environments from the internet for demonstration purposes. No personal data is sent during these requests. The only other network activity is the in-app feedback feature, described next.

## In-App Feedback (opt-in)

The App includes a **feedback button** that lets you report a bug or share an idea by recording your screen and voice. This feature is entirely optional and **consent-gated** — it does nothing until you tap the feedback button, work through its consent screens, and approve Android's own screen-capture dialog. You can cancel at any point and discard a recording before it is sent.

### What is captured

When you submit feedback:

| Data | Detail |
|------|--------|
| Screen recording | A short `.mp4` of the app screen. It may include the AR camera viewfinder if an AR demo is open. |
| Microphone audio | Your voice narration describing the bug or idea. |
| Typed text | Any optional description you type. |
| Device & app context | App version and build number, Android version and API level, device manufacturer and model, locale, and free RAM. No advertising ID, no IP address, no account identifier. |

For an **idea**, a screen recording is optional — a quick voice note or plain text is enough. A recording is encouraged only for **bug** reports.

### Where it goes

The recording, audio, and context are uploaded over HTTPS to the **SceneView feedback service** — an open-source Cloudflare Worker operated by the SceneView project ([`feedback-worker/`](https://github.com/sceneview/sceneview/tree/main/feedback-worker)). The service then:

1. Stores the screen recording and audio **privately** in **Cloudflare R2** object storage (a private bucket, not publicly listable or indexable).
2. Transcribes your voice audio to text using **Cloudflare Workers AI** (the OpenAI **Whisper** model). The audio is processed for transcription only and is not used to train any model.
3. Creates a **public GitHub issue** in the [sceneview/sceneview](https://github.com/sceneview/sceneview) repository so maintainers can triage it.

### What becomes public

The GitHub issue created from your feedback contains **only the text transcript of your audio, any text you typed, and the device/app context table**. The **raw screen recording and audio are never attached to the public issue.** Please avoid showing or saying personal information while recording, since the transcript and context are public.

### Access control and retention

The screen recording and audio are accessible only to SceneView maintainers, through an **admin-token-gated viewer**. Without that token, the viewer page shows only the public transcript and context — the media players are not available. Media is served with `private, no-store` caching and the viewer page is marked `noindex`.

The screen recording and audio are **automatically deleted after 90 days** by a scheduled job. After deletion, only the transcript and context remain (the same information already on the public GitHub issue). The service hashes the request IP address for rate limiting only — the raw IP is never stored.

### Third-party processors for this feature

| Service | Role | Data handled |
|---------|------|--------------|
| Cloudflare R2 | Private object storage | Screen recording, audio |
| Cloudflare Workers AI (Whisper) | Speech-to-text transcription | Audio |
| GitHub | Public issue tracker | Transcript, typed text, device/app context |

## Third-Party Services

The App uses the following platform services:

- **ARCore** (Android): Google's AR platform. Subject to [Google's Privacy Policy](https://policies.google.com/privacy).
- **ARKit** (iOS): Apple's AR framework. Subject to [Apple's Privacy Policy](https://www.apple.com/privacy/).
- **Cloudflare** (feedback feature only): hosts the feedback service, R2 storage, and Workers AI transcription. Subject to [Cloudflare's Privacy Policy](https://www.cloudflare.com/privacypolicy/).
- **GitHub** (feedback feature only): hosts the public issue created from feedback. Subject to [GitHub's Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).

## Data Sharing

- Outside the feedback feature, no data is shared with third parties.
- For the feedback feature, data is shared **only** with the processors listed above, and the feedback transcript + context become **public** on a GitHub issue. Nothing is sold or used for advertising.

## Open Source

The App is fully open source. You can review the complete source code — including the feedback service — at [github.com/sceneview/sceneview](https://github.com/sceneview/sceneview).

## Contact

For privacy questions, open an issue on our [GitHub repository](https://github.com/sceneview/sceneview/issues).
