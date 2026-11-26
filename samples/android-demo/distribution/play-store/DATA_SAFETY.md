# Play Store Data safety — SceneView demo

Source of truth for the **Data safety** form of `io.github.sceneview.demo` in Google Play
Console (*Policy and programs → App content → Data safety*). The importable version is
[`data-safety.csv`](data-safety.csv) — Play Console *Import from CSV*, or the Play
Developer API:

```bash
# Body: {"safetyLabels": "<the whole CSV as one string>"}
POST https://androidpublisher.googleapis.com/androidpublisher/v3/applications/io.github.sceneview.demo/dataSafety
```

The privacy policy these answers must match is <https://sceneview.github.io/privacy.html>
(source: [`website-static/privacy.html`](../../../../website-static/privacy.html)).

> **Why the answers changed.** Until 4.51 the form said "no data collected". The Firebase
> release adds Google Analytics for Firebase, Crashlytics and Cloud Messaging. The same
> review also caught what the old form missed: the app requests `ACCESS_FINE_LOCATION`,
> and Google's [ARCore Data safety guidance](https://developers.google.com/ar/develop/play-safety-label)
> says ARCore's cloud features (Cloud Anchors, Geospatial) send camera and precise
> location data to Google, and that ARCore always collects its own identifiers and
> diagnostics while AR runs.
>
> **Checked again against 4.75.0.** One answer changed: *App interactions* also serves
> *App functionality*, because ARCore collects its API usage to keep AR working, not only
> for analytics. Everything else still matches the code; the sources below now name every
> SDK behind each answer (Streetscape, ML Kit), and the
> [features that touch the network without being collection](#checked-not-declared) are
> listed with the reason they stay off the form.

---

## Section 1 — Data collection and security

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (Firebase and ARCore use HTTPS/TLS) |
| Which account creation methods does your app support? | **None** — the app has no accounts |
| Do you provide a way for users to request that their data is deleted? | **Yes** — by email, see [privacy.html#your-choices](https://sceneview.github.io/privacy.html#your-choices) |

## Section 2 — Data types

All **collected**, none **shared** (Google processes Firebase data as a service provider
on the developer's behalf, which Play excludes from "sharing"), none processed
ephemerally. "Optional" = the user can turn it off (the Settings switch for analytics
and crash reports, the OS permission or the demo itself for the rest).

| Category > Type | Required / optional | Purposes | Source |
|---|---|---|---|
| Location > Approximate location | Optional | Analytics, App functionality | GA4 derives it from the masked IP; ARCore |
| Location > Precise location | Optional | App functionality | ARCore Geospatial, after the location permission: the Geospatial anchors demo (`ARGeospatialAnchorsDemo`, `GeospatialMode.ENABLED`) and Streetscape (`ARSceneGeometryDemo`, `StreetscapeGeometryMode.ENABLED`) |
| Personal info > User IDs | Optional | Analytics | ARCore's own user ID while AR runs (the app has no accounts) |
| Photos and videos > Videos | Optional | App functionality | Camera data ARCore sends to host and resolve Cloud Anchors (`hostCloudAnchorAsync`, `resolveCloudAnchorAsync` in `ARCloudAnchorDemo`) and for Geospatial and Streetscape localization. Not ephemeral: resolving is processed in memory only, but hosting and Geospatial data are stored for a while before Google deletes them |
| Financial info > Purchase history | Optional | Analytics | The Firebase Analytics events of the in-app support purchases, with their product ID or tier (`AnalyticsEvent`, `SupportPromptAnalytics`). Declared because 4.75.0 sends them; #5300 removes them from Analytics, and this row goes once a release with #5300 is live |
| App activity > App interactions | Optional | Analytics, App functionality | Firebase Analytics events; ARCore's API usage and app activity, which Google uses for analytics and to keep AR working |
| App activity > In-app search history | Optional | Analytics | The `search` event's `search_term`: lowercased, cut to 50 characters, emails, links and long or phone-like numbers redacted on the device |
| App info and performance > Crash logs | Optional | Analytics, App functionality | Crashlytics |
| App info and performance > Diagnostics | Optional | Analytics, App functionality | Crashlytics; ARCore; ML Kit object detection and the ML Kit GenAI Prompt API (Gemini Nano), whose own diagnostics go to Google whatever the app's analytics switch says ([ML Kit disclosure](https://developers.google.com/ml-kit/android-data-disclosure)) |
| Device or other IDs | Required | App functionality, Analytics, Developer communications | Firebase app instance ID, installation ID, FCM token; ARCore's and ML Kit's per-installation identifiers |

**Not collected:** name, email, phone, address, other personal info, financial info other than
the purchase history above (no card or payment details), health, messages, photos, audio, files, calendar, contacts, web browsing, installed apps,
other user-generated content. **No advertising ID**: the merged manifest
must not carry `com.google.android.gms.permission.AD_ID` (firebase-analytics adds it;
remove it with `tools:node="remove"`) and must set
`google_analytics_adid_collection_enabled=false`, so the *Advertising ID* declaration in
App content stays **No**. Check the merged release manifest before every upload.

## Checked, not declared

Play counts as *collection* only what the app, or an SDK inside it, sends off the device.
These features use the network or sensitive input without doing that; each one would
change the form if its code changed.

| Feature | Why it is not on the form |
|---|---|
| Ask in AR and voice dictation (`RECORD_AUDIO`) | The question is heard by the system `SpeechRecognizer` (`AskInArDemo`) or the system recognizer activity (`VoiceDictation`). The phone's speech-recognition service captures the audio and may send it to its provider under its own policy; the app only receives the text, which stays on the device. Declare *Audio* if the app ever records or uploads audio itself. |
| Ask in AR answers | Picked on the device by `ScriptedStreamingAnswerProvider`. The optional proxy (`ProxyStreamingAnswerProvider`, which would post the question, room measurements and a camera frame) only exists in a build made with `ask-ar-live.init.gradle`, which no release workflow uses. A build that enables it needs *Photos*, *Other user-generated content* and an update to the privacy policy first. |
| Point & Ask | The camera frame and the question go to Gemini Nano on the device (ML Kit GenAI Prompt API, `AskEngine`). Only ML Kit's own diagnostics leave the device (see *Diagnostics*). |
| Downloads | The HD model pack is fetched on Wi-Fi at launch (`HdPack.schedulePrefetch`, from `MainActivity`); the 3D Scan's recognition model (`SceneRecognitionAndroid`) and Gemini Nano (through AICore) are downloaded on first use; the Media demo streams its video from GitHub; Explore browses and downloads models from public galleries. These requests carry no identifier or user data; the servers see the IP address, as for any download. |
| Google Play and ARCore services | In-app update checks and the ARCore availability check go through Google Play services, not the app. |
| In-app bug reporter | See below. |

## Section 3 — Security practices

- Encrypted in transit: **Yes**.
- Deletion: on request by email; data also expires on its own (GA4 event data 14 months,
  Crashlytics 90 days, FCM tokens when invalidated or unused for 270 days).

## In-app bug reporter

Unchanged and still not "collection": it composes the report on-device and the user
sends it through the Android share sheet or a GitHub issue they submit themselves.
