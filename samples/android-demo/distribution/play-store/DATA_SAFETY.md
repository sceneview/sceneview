# Play Store Data safety — SceneView demo

Reference content for the **Data safety** form in Google Play Console
(*App content → Data safety*). Transcribe these answers into the Play Console
questionnaire — this file is the source of truth, not a file the CI uploads.

> **Why this exists.** The demo app's optional **in-app feedback** feature
> (umbrella [#1930](https://github.com/sceneview/sceneview/issues/1930)) records
> the screen and microphone and uploads them to the SceneView feedback service.
> That is data collection in Play's terms, so the Data safety form must declare
> it — even though the feature is strictly opt-in. The data flow is described in
> [`.github/PRIVACY_POLICY.md`](../../../../.github/PRIVACY_POLICY.md) and
> [`feedback-worker/DEPLOY.md`](../../../../feedback-worker/DEPLOY.md).

---

## Section 1 — Data collection and security

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** — every feedback upload is sent over HTTPS. |
| Do you provide a way for users to request that their data be deleted? | **Yes** — see *Data deletion* below. Recordings also auto-delete after 90 days. |

---

## Section 2 — Data types collected

Only the data types below are collected, and **only when the user explicitly
uses the in-app feedback feature**. Normal app use (browsing 3D/AR demos)
collects nothing.

For every data type below:

- **Collected:** Yes — **Shared:** Yes (sent to Cloudflare for hosting/transcription
  and surfaced on a public GitHub issue — see *Data sharing* below).
- **Processing:** sent off-device (not processed ephemerally — the recording is
  stored for up to 90 days).
- **Required or optional:** **Optional** — users choose whether to send feedback
  at all; the whole feature is opt-in and consent-gated.
- **Purpose:** **App functionality** (the feedback/bug-reporting feature). Not
  used for analytics, advertising, personalisation, or account management.

### Audio → "Voice or sound recordings"

- **What:** The user's microphone audio recorded while they narrate a bug or
  idea, plus the audio track of the screen recording.
- **Why:** So maintainers can hear the user describe the problem; the audio is
  transcribed to text (Cloudflare Workers AI / Whisper) to pre-fill a GitHub
  issue.

### Photos and videos → "Videos"

- **What:** A short screen recording (`.mp4`) of the app while the user records
  feedback. It can include the AR camera viewfinder if an AR demo is open.
- **Why:** So maintainers can see exactly what the user did and reproduce the
  bug.

> Map this to the **"Videos"** data type in the Play form. The recording is a
> capture of the app's own screen, not the user's photo library — the app never
> reads the gallery.

### App info and performance → "Diagnostics"

- **What:** Device/app context attached to each feedback submission: app
  version and build number, Android version and API level, device manufacturer
  and model, device locale, and free RAM at submission time.
- **Why:** So maintainers know which build, OS, and device the report came from.

> No advertising ID, no IP address, no account/user identifier, and no
> persistent device identifier are collected or stored. The feedback service
> hashes the request IP transiently for rate limiting only and never stores the
> raw IP — there is nothing to declare under *Device or other IDs*.

### Data types NOT collected

For completeness, the app does **not** collect any of: Location, Personal info
(name, email, user IDs, address, phone), Financial info, Health & fitness,
Messages, Contacts, Calendar, Files & docs, Web browsing history, Installed
apps, Search history, or Device/other IDs.

---

## Section 3 — Data sharing

The feedback data **is shared** with third parties, so answer **"Yes"** to data
sharing for the Audio, Videos, and Diagnostics types above.

| Recipient | What is shared | Why |
|---|---|---|
| Cloudflare | Screen recording + audio (private R2 storage); audio (Workers AI transcription) | Hosting and speech-to-text for the feedback service. |
| GitHub | Transcript of the audio, any typed text, and the device/app context | A pre-filled **public** GitHub issue is created so maintainers can triage. The raw recording and audio are **not** posted publicly. |

In Play's "data sharing" sense this counts as **transfer to service providers**
plus, for the transcript + context, **publicly visible** information (it appears
on a public GitHub issue). The user is told this on the in-app consent screen
and in the privacy policy before they submit.

The data is **not** sold, and **not** shared for advertising or analytics.

---

## Section 4 — Security practices

- **Encryption in transit:** Yes — all uploads use HTTPS.
- **At rest:** Screen recordings and audio are stored in a **private** Cloudflare
  R2 bucket (not publicly listable or indexable). Playback is gated behind an
  admin token; without it, the viewer page exposes only the transcript and
  context.
- **Retention:** Screen recordings and audio are **automatically deleted after
  90 days** by a scheduled job. After deletion only the transcript and context
  remain (the same information already on the public GitHub issue).

---

## Section 5 — Data deletion

- **Automatic:** Recordings and audio auto-purge after 90 days.
- **On request:** A user can ask for earlier deletion by opening an issue at
  <https://github.com/sceneview/sceneview/issues> or emailing
  `thomas.gorisse@gmail.com`, quoting the feedback ID shown on the GitHub issue
  created from their submission. Maintainers can delete the R2 media and the
  feedback record on request.

When the Play Console asks for a **data deletion URL**, use:
<https://github.com/sceneview/sceneview/issues/new>

---

## Privacy policy URL

Set the Data safety form's privacy-policy link to the published policy:

<https://sceneview.github.io/privacy.html>

(Source: [`docs/docs/privacy.md`](../../../../docs/docs/privacy.md) and
[`.github/PRIVACY_POLICY.md`](../../../../.github/PRIVACY_POLICY.md).)

---

## Content rating note

The `PLAY_STORE_SETUP.md` content-rating questionnaire previously answered "No"
to *user data collection*. Once the feedback feature ships, the content-rating
questionnaire's data-collection question must be answered **Yes** to stay
consistent with this Data safety declaration.
