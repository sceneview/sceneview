# Google Play Store Setup — SceneView

Step-by-step guide to publish the SceneView app on Google Play.

---

## 1. Prerequisites

- [ ] Google Play Developer account ($25 one-time fee) → [Register here](https://play.google.com/console/signup)
- [ ] Access to the SceneView GitHub repository with admin rights (for secrets)

---

## 2. Create the App on Google Play Console

1. Go to [Google Play Console](https://play.google.com/console)
2. Click **"Create app"**
3. Fill in:
   - **App name:** `SceneView`
   - **Default language:** English (United States)
   - **App or game:** App
   - **Free or paid:** Free
4. Accept declarations and click **Create app**

---

## 3. Complete the Store Listing

The Play Store listing is pre-configured in `distribution/play-store/` — the
single source of truth consumed by the `play-store.yml` listing-sync job
(text **and** graphics):

```
distribution/play-store/
└── en-GB/
    ├── title.txt
    ├── short_description.txt
    ├── full_description.txt
    └── graphics/
        ├── feature-graphic.png            (1024×500)
        ├── icon-512.png                   (512×512)
        ├── phone-screenshot-{1..5}.png    (1080×2304)
        ├── tablet7-screenshot-{1..5}.png  (1200×N, portrait)
        └── tablet10-screenshot-{1..5}.png (2560×N, landscape)
```

The five screenshots of each class are the same five demos, in the same order,
as the iOS listing — the unified showcase set (#2773). Tablets keep their
native post-crop height rather than being padded to the phone ratio.

On every `vX.Y.0` tag release, the `sync-listing` job in
`.github/workflows/play-store.yml` PATCHes the listing text and uploads the
graphics above via the Play `edits.images` API — no manual Play Console
upload is needed. Refresh the screenshots with
`.claude/scripts/capture-play-store-screenshots.sh`, which writes into the
same `graphics/` directory. Pass `--form-factor tablet7` / `tablet10` for the
tablet slots (#2796); the default is `phone`.

Tablet screenshots (`tablet7-screenshot-*.png` / `tablet10-screenshot-*.png`)
**are** in the repo and the listing-sync job uploads them automatically —
`play_listing.py` maps them onto Play's `sevenInchScreenshots` /
`tenInchScreenshots` image types.

Optional extras still not in the repo:
- **Promo video:** YouTube link — set manually in the Play Console.

### Content Rating

1. Go to **Policy → App content → Content rating**
2. Start questionnaire → Category: **Utility / Productivity**
3. No violence or mature content. Answer the **user-data-collection** question
   **No** — the app collects no user data (the in-app bug reporter runs
   entirely on-device and only the user, by their own action, sends a report;
   see *Data safety* below).
4. Apply rating

### Data safety

The app collects no user data. The in-app bug reporter composes its report
on-device and hands it to the user, who chooses whether to send it via the
Android share sheet or a pre-filled GitHub issue they submit — a user-initiated
transfer that Play does not count as app collection or sharing. The exact
answers to transcribe into the questionnaire (all "No") are in
[`distribution/play-store/DATA_SAFETY.md`](distribution/play-store/DATA_SAFETY.md).

### Privacy Policy

A privacy policy is still linked for transparency even though the app collects
no user data. Use the published policy at
<https://sceneview.github.io/privacy.html> (source:
[`docs/docs/privacy.md`](../../docs/docs/privacy.md) and
[`.github/PRIVACY_POLICY.md`](../../.github/PRIVACY_POLICY.md)).

---

## 4. Generate a Signing Keystore

```bash
keytool -genkeypair \
  -alias sceneview-demo \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -keystore sceneview-demo.keystore \
  -storepass YOUR_STORE_PASSWORD \
  -keypass YOUR_KEY_PASSWORD \
  -dname "CN=SceneView, O=SceneView, L=Paris, C=FR"
```

**Important:** Keep this keystore file secure. You'll need it for every future release.

---

## 5. Configure Google Play App Signing

1. In Play Console → **Setup → App signing**
2. Choose **"Use Google-generated key"** (recommended)
3. Upload your keystore's **upload key certificate** when prompted

---

## 6. Set up a Service Account for CI/CD

1. Go to [Google Cloud Console](https://console.cloud.google.com)
2. Create or select a project linked to your Play Console
3. Navigate to **IAM & Admin → Service Accounts**
4. Click **Create Service Account**:
   - Name: `sceneview-play-publisher`
   - Role: none (we'll grant Play Console access instead)
5. Create a JSON key → Download it
6. In **Google Play Console → Setup → API access**:
   - Link the Google Cloud project
   - Grant the service account access with **"Release manager"** permission

---

## 7. Configure GitHub Secrets

Go to your GitHub repo → **Settings → Secrets and variables → Actions** and add:

| Secret | Value |
|--------|-------|
| `DEMO_KEYSTORE_BASE64` | Base64-encoded keystore: `base64 -i sceneview-demo.keystore` |
| `DEMO_KEYSTORE_PASSWORD` | Your keystore password |
| `DEMO_KEY_ALIAS` | `sceneview-demo` |
| `DEMO_KEY_PASSWORD` | Your key password |
| `PLAY_STORE_SERVICE_ACCOUNT_JSON` | Full content of the service account JSON key file |

---

## 8. First Release (Manual)

The first release **must** be uploaded manually:

1. Build the release AAB locally:
   ```bash
   export SCENEVIEW_DEMO_KEYSTORE_FILE=path/to/sceneview-demo.keystore
   export SCENEVIEW_DEMO_KEYSTORE_PASSWORD=YOUR_STORE_PASSWORD
   export SCENEVIEW_DEMO_KEY_ALIAS=sceneview-demo
   export SCENEVIEW_DEMO_KEY_PASSWORD=YOUR_KEY_PASSWORD
   ./gradlew :samples:android-demo:bundleRelease
   ```
2. Find the AAB at `samples/android-demo/build/outputs/bundle/release/`
3. In Play Console → **Production → Create new release**
4. Upload the AAB
5. Add release notes
6. **Review and roll out** → Start with internal testing first

---

## 9. Automated Releases (After First)

Once the first release is live, automated deployment works via GitHub Actions:

### Option A: Tag-based release
```bash
# Bump versionCode and versionName in build.gradle first
git tag demo-v1.1.0
git push origin demo-v1.1.0
```
This triggers the workflow and publishes to the **internal** track.

### Option B: Manual workflow dispatch
1. Go to **Actions → "Deploy Demo to Play Store"**
2. Click **Run workflow**
3. Select the track: `internal`, `alpha`, `beta`, or `production`

### Promotion Flow
Recommended promotion path:
```
internal → alpha → beta → production
```

Promote in Play Console: **Release → Testing → [track]** → Promote to next track.

---

## 10. Version Management

Before each release, update `samples/android-demo/build.gradle`:

```groovy
defaultConfig {
    versionCode 2        // Increment for each release
    versionName "1.1.0"  // Semantic version
}
```

Release notes ("What's new") are extracted automatically from the matching
`## vX.Y.Z` section of the root `CHANGELOG.md` by the `play-store.yml`
workflow — there is no separate release-notes file to maintain.

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| "Package name not found" | First release must be done manually |
| "APK/AAB not signed correctly" | Check keystore env vars and signing config |
| "Service account unauthorized" | Verify Play Console API access + permissions |
| "Version code already used" | Increment `versionCode` in build.gradle |
| Upload action fails with 403 | Service account needs "Release manager" role |
