# feedback-worker — deployment

Cloudflare worker behind the SceneView in-app feedback feature
(umbrella [#1930](https://github.com/sceneview/sceneview/issues/1930),
task [#1931](https://github.com/sceneview/sceneview/issues/1931)).

It receives feedback uploads from the demo apps, stores the media privately in
R2, transcribes the audio with Workers AI (Whisper), and opens a pre-filled
GitHub issue. The public issue carries only the transcript + context; the raw
recording is viewable only through the admin-gated `/feedback/<id>` viewer.

## 1. Prerequisite — GitHub App

Create a GitHub App on the **sceneview** org (Settings → Developer settings →
GitHub Apps → New GitHub App):

- Repository permissions → **Issues: Read and write**
- Webhook → **Active: unchecked**
- Install it on `sceneview/sceneview`

Collect: the **App ID**, the **Installation ID** (the number in the install
URL), and a generated **private key** (`.pem`).

GitHub issues the key in PKCS#1 format; the worker needs PKCS#8 — convert it:

```sh
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt \
  -in your-app.private-key.pem -out app.pkcs8.pem
```

Keep `app.pkcs8.pem` out of the repo — it goes straight into a Wrangler secret.

## 2. Provision the bindings

```sh
npm install
wrangler d1 create sceneview-feedback            # paste database_id into wrangler.toml
wrangler kv namespace create RL_KV               # paste id into wrangler.toml
wrangler r2 bucket create sceneview-feedback-media
wrangler d1 migrations apply sceneview-feedback
```

## 3. Secrets

```sh
wrangler secret put GITHUB_APP_ID
wrangler secret put GITHUB_INSTALLATION_ID
wrangler secret put GITHUB_PRIVATE_KEY < app.pkcs8.pem
wrangler secret put ADMIN_TOKEN                  # any long random string
```

`ADMIN_TOKEN` gates media playback in the `/feedback/<id>` viewer — without it,
the viewer shows only the transcript + context. Keep it private.

## 4. Verify and deploy

```sh
npm run typecheck
npm test
wrangler deploy
```

## Endpoints

| Route | Purpose |
|---|---|
| `POST /v1/feedback` | Multipart upload from the demo apps (video + audio + context). |
| `GET /feedback/<id>` | Viewer — transcript + context public; media admin-gated. |
| `GET /feedback/<id>/media/<video\|audio>` | Admin-token-gated media stream. |
| `GET /health` | Health check. |

## Retention

Media older than 90 days is purged nightly by the cron trigger
(`0 3 * * *`). The feedback row and its transcript are kept.

## Cost

Free tier at SceneView's volume: Workers AI Whisper (free allocation), R2
(< 10 GB with 90-day retention, free egress), GitHub API (free).
