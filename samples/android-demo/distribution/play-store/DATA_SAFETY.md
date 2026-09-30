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
| Location > Precise location | Optional | App functionality | ARCore Geospatial demos, after the location permission |
| Personal info > User IDs | Optional | Analytics | ARCore's own user ID while AR runs (the app has no accounts) |
| Photos and videos > Videos | Optional | App functionality | Camera data ARCore sends to host/resolve Cloud Anchors and for Geospatial localization; marked for automatic deletion |
| App activity > App interactions | Optional | Analytics | Firebase Analytics events |
| App info and performance > Crash logs | Optional | Analytics, App functionality | Crashlytics |
| App info and performance > Diagnostics | Optional | Analytics, App functionality | Crashlytics, ARCore |
| Device or other IDs | Required | App functionality, Analytics, Developer communications | Firebase app instance ID, installation ID, FCM token |

**Not collected:** name, email, phone, address, other personal info, financial info,
health, messages, photos, audio, files, calendar, contacts, web browsing, installed apps,
search history, other user-generated content. **No advertising ID**: the merged manifest
must not carry `com.google.android.gms.permission.AD_ID` (firebase-analytics adds it;
remove it with `tools:node="remove"`) and must set
`google_analytics_adid_collection_enabled=false`, so the *Advertising ID* declaration in
App content stays **No**. Check the merged release manifest before every upload.

## Section 3 — Security practices

- Encrypted in transit: **Yes**.
- Deletion: on request by email; data also expires on its own (GA4 event data 14 months,
  Crashlytics 90 days, FCM tokens when invalidated or unused for 270 days).

## In-app bug reporter

Unchanged and still not "collection": it composes the report on-device and the user
sends it through the Android share sheet or a GitHub issue they submit themselves.
