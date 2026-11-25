# Ask in AR proxy

A small native-app-only Cloudflare Worker. The phone never receives a model key.
This directory is deployment-ready source; deployment and the real-model smoke
test are Thomas's steps. Nothing is deployed by installing or testing it.

## Deploy

Use Node.js 22 or newer. In this directory, run:

```sh
npm i
npm test
npm run typecheck
npx wrangler login
npx wrangler secret put ANTHROPIC_API_KEY
npx wrangler deploy
```

`ANTHROPIC_API_KEY`: `<set with wrangler secret put>`.
Enter it only at Wrangler's secret prompt. Do not put it in this repository,
example files, command arguments, logs, an Android property, or the APK.
SDK logging is explicitly disabled; upstream error bodies are not forwarded.

Wrangler prints an origin like `https://ask-ar-proxy.<your-subdomain>.workers.dev`.
Give that **origin** to the demo integrator (no `/ask` suffix and no key).
See [Android wiring](../../samples/android-demo/ASK_AR_LIVE.md).

## Wire protocol

```http
POST /ask
Content-Type: application/json
X-Ask-Client: sceneview-ask-ar-v1

{"question":"How far is that wall?","context":{"distanceAheadMeters":2.4}}
```

Successful requests return `text/event-stream; charset=utf-8`. Every SSE event
is one JSON object followed by a blank line. JSON escaping keeps embedded
newlines and quotes inside text deltas valid:

```text
data: {"t":"About *2.5"}

data: {"t":" metres*. Mind the wall!"}

data: {"done":true,"doodle":"circle","emphasis":"2.5 metres"}

```

The official `@anthropic-ai/sdk` client calls `client.messages.stream()` with
`claude-haiku-5-5`, `max_tokens: 200`, `output_config: {effort: "low"}`, and
`thinking: {type: "disabled"}`. Only text deltas are forwarded. The final message
must have `stop_reason: "end_turn"`; refusal, truncation and empty answers emit
`data: {"error":"..."}` instead of `done`. Transport and SDK errors are generic.
Validation errors use the same error-event shape with an appropriate HTTP status.
Cancellation aborts the SDK stream; retries are disabled to keep startup latency low.

The prompt asks for ASCII, at most 14 words, 1–2 short sentences, a warm playful
tone and no formatting beyond optional `*emphasis*`. This is prompt guidance,
not a post-generation rewrite: raw deltas arrive immediately. Measurement context
is supplied separately in the user JSON and used only for distance/size questions.

Doodles are deterministic, chosen from the final text: any number wins `circle`;
otherwise a recommendation keyword (`try`, `take`, `choose`, `use`, `recommend`,
`should`, `go for`, `pick`) selects `underline`; otherwise `arrow`. Emphasis is
the first asterisk-delimited phrase, else the first number with optional units,
else the full reply. Asterisks stay in streamed text and are hidden by Android.
No trailing tag needs buffering. The protocol also accepts `none` on Android.

## Abuse limits and cost

- `X-Ask-Client` is a public static filter, **not authentication**. Change
  `ASK_CLIENT_TAG` and `AskArLiveConfig.CLIENT_TAG` together if needed.
- Cloudflare's Workers Rate Limiting binding allows about 10 requests per IP per
  60 seconds, keyed by Cloudflare's `CF-Connecting-IP`, not forwarded client data.
  This is approximate and local to each Cloudflare location, not a global spend cap.
  `namespace_id = "735501"` must be unused by other rate limiters in the account;
  choose another integer before deployment if it is already assigned.
- Missing IP or unavailable limiter fails closed. Questions are nonempty and at
  most 200 UTF-16 code units (matching Android); actual JSON bodies are capped at
  4096 bytes, even without `Content-Length`.
- CORS is off: no access-control headers, no OPTIONS handler, and requests carrying
  `Origin` are rejected. Non-browser clients can still imitate the public tag.
- Haiku 5.5 costs $0.10/M input and $0.50/M output tokens for these short prompts.
  For example, 300 input + 50 output tokens cost about $0.000055 (0.0055 cents):
  fractions of a cent per answer, excluding Cloudflare fees. No image is sent.

References: [official SDK](https://github.com/anthropics/anthropic-sdk-typescript),
[Haiku model and pricing](https://platform.claude.com/docs/en/models/haiku-5-5/overview),
[effort](https://platform.claude.com/docs/en/build-with-claude/effort),
[Cloudflare rate limits](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/).

## Smoke test after deployment

```sh
curl --no-buffer 'https://ask-ar-proxy.<your-subdomain>.workers.dev/ask' \
  -H 'Content-Type: application/json' \
  -H 'X-Ask-Client: sceneview-ask-ar-v1' \
  --data '{"question":"How far is that wall?","context":{"distanceAheadMeters":2.4}}'
```

Expect multiple text events before one terminal `done` (or an `error`). Tests mock
the SDK and do not spend API credits or demonstrate real account/model access.
