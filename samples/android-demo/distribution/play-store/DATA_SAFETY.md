# Play Store Data safety — SceneView demo

Reference content for the **Data safety** form in Google Play Console
(*App content → Data safety*). Transcribe these answers into the Play Console
questionnaire — this file is the source of truth, not a file the CI uploads.

> **Why this exists.** The demo app's only feature that touches user-provided
> data is the optional **in-app bug reporter**. It runs **entirely on-device**:
> no runtime permission, no screen recording, no microphone, no background
> service, and no upload to any SceneView-operated server. The report (device
> info, the app's own recent log, and an optional screenshot of the app window)
> is handed to the user, who chooses whether to send it — through the Android
> **system share sheet**, or as a **pre-filled GitHub issue they review and
> submit themselves**. Because the app never transmits this data off the device
> on its own — the only egress is a user-initiated share/submit — it is **not
> "collected" or "shared" in Play's terms**. The flow is described in
> [`.github/PRIVACY_POLICY.md`](../../../../.github/PRIVACY_POLICY.md).

---

## Section 1 — Data collection and security

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |

The app does not collect or share user data. Normal use (browsing the 3D/AR
demos) transmits nothing off-device, and the in-app bug reporter only assembles
a report locally and hands it to the user; any transfer is a **user-initiated**
share-sheet action or a GitHub issue the user submits themselves. Under Play's
Data safety definitions, data moved only by the user's own action through the
Android share functionality (and content the user chooses to post to a public
issue tracker) is **not** app "collection" or "sharing", so the remaining
sections of the questionnaire do not apply.

---

## Section 2 — Data types collected

**None.** No data type in the Play catalogue is collected by the app:

- **Not collected:** Location, Personal info (name, email, user IDs, address,
  phone), Financial info, Health & fitness, Messages, Photos & videos, Audio,
  Contacts, Calendar, Files & docs, App activity, Web browsing history,
  Installed apps, Search history, App info & performance / Diagnostics, and
  Device or other IDs.

The bug reporter reads only data the app already holds about *itself* (its own
version/build, the current device model / OS / ABI / screen metrics, and the
tail of the app's own logcat), plus an optional screenshot of the app's own
window. This information is placed into a report and shown to the user; the app
never sends it anywhere by itself, so there is nothing to declare as collected.

> The screenshot is a capture of the app's **own** window (via `PixelCopy`),
> not the user's photo library — the app never reads the gallery and holds no
> media/storage permission.

---

## Section 3 — Data sharing

**Not applicable.** The app does not share user data with third parties. When the
user opens the share sheet or the pre-filled GitHub issue, *they* choose the
recipient and initiate the transfer; Play excludes user-initiated share-sheet
transfers from "sharing". The app itself sends nothing to any server, and no data
is sold or shared for advertising or analytics.

---

## Section 4 — Security practices

- **Encryption in transit:** Not applicable — the app performs no data upload.
  Anything the user chooses to send travels over the transport of the app they
  picked in the share sheet (e.g. GitHub over HTTPS).
- **At rest:** The optional screenshot is written to the app's private cache
  (`FileProvider`, `cache/feedback/`) only long enough for the share sheet to
  read it, and is swept on app start; nothing is stored on any server.

---

## Section 5 — Data deletion

Not applicable — the app stores no user data on a server, so there is nothing to
delete server-side. A user who submitted a public GitHub issue can edit or ask
maintainers to remove it at
<https://github.com/sceneview/sceneview/issues>.

When the Play Console asks for a **data deletion URL** (only if it insists), use:
<https://github.com/sceneview/sceneview/issues/new>

---

## Privacy policy URL

Set the Data safety form's privacy-policy link to the published policy:

<https://sceneview.github.io/privacy.html>

(Source: [`docs/docs/privacy.md`](../../../../docs/docs/privacy.md) and
[`.github/PRIVACY_POLICY.md`](../../../../.github/PRIVACY_POLICY.md).)

---

## Content rating note

The content-rating questionnaire's *user-data-collection* question is answered
**No** — consistent with this Data safety declaration. (Earlier versions of the
app shipped a screen-and-microphone recorder that uploaded to a SceneView
feedback service and required a **Yes**; that capability has been retired.)
