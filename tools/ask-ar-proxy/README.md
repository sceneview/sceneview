# Ask in AR proxy

A small native-app-only Cloudflare Worker. The phone never receives a model key.
This directory is deployment-ready source. Nothing is deployed by installing or
testing it: deploying it, and holding its model key, is the deployer's job.

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
Give that **origin** to the demo build (no `/ask` suffix and no key): a debug
build takes it at launch with `--es ask_proxy <origin>`, and a release build
from the opt-in `samples/android-demo/ask-ar-live.init.gradle`. Without it, the
demo answers from its scripted, on-device answers.

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
not a post-generation rewrite: raw deltas arrive immediately. Facts are in a delimited `<room_facts>` JSON block, explicitly data rather than
instructions. Numbers must come from those facts, with unit conversion/rounding
only (cm below 1 m, otherwise m to one decimal). Missing measurements prompt a
request for the needed information. An image can support visible descriptions;
people must never be identified. The answer follows the question's language,
regardless of locale. These are model instructions, not a numeric output validator.

Doodles are deterministic, chosen from the final text: any number wins `circle`;
otherwise a recommendation keyword (`try`, `take`, `choose`, `use`, `recommend`,
`should`, `go for`, `pick`) selects `underline`; otherwise `arrow`. Emphasis is
the first asterisk-delimited phrase, else the first number with optional units,
else the full reply. Asterisks stay in streamed text and are hidden by Android.
No trailing tag needs buffering. The protocol also accepts `none` on Android.

## Real context and privacy

`context` is optional. Accepted fields (omitted facts remain unknown):

```json
{
  "distanceAheadMeters": 2.4,
  "surface": {"kind": "wall", "widthMeters": 2.1, "heightMeters": 1.3},
  "cameraHeightMeters": 1.6,
  "localTime": "2026-11-25T14:30:00+01:00",
  "locale": "fr-FR",
  "image": {"mediaType": "image/jpeg", "data": "<base64 JPEG>"}
}
```

Surface kinds are `wall`, `floor`, `table`, `ceiling`, `other`. Extents describe
only the detected plane, not the full wall or room. Android scales the frame to
at most 768 px on the long side and encodes JPEG at quality 70. The Worker checks
media type and base64 length/syntax; it does not decode images to verify dimensions.

Only when live mode is on, each question sends its text, available AR measurements,
phone local time/offset and locale, and **one camera frame per question** if ARCore
makes it available. There is no continuous video upload. Scripted mode sends
nothing. The Worker sends the frame and facts to Anthropic to answer that question.
No device GPS permission or GPS fix is used. Cloudflare supplies approximate IP
location, city and country. Coordinates are never added to the model facts.

Weather-related/outdoor keywords in English or French trigger an Open-Meteo current
conditions lookup. Both coordinates are rounded to one decimal before the request
and Cache API key; the normalized result is cached for 10 minutes. Open-Meteo sees
only those rounded coordinates, never the question or image. The entire lookup has
an 800 ms deadline; missing location, errors or timeouts silently omit weather.
Unrelated questions do not read the weather cache or contact Open-Meteo. Conditions
are approximate, outdoors and current; they cannot establish indoor temperature
or predict later rain. Code descriptions follow [Open-Meteo's WMO table](https://open-meteo.com/en/docs).

The Worker never logs questions, frames, coordinates or upstream error bodies.
Only normalized weather facts enter the Cache API; images and questions are not
cached by this code. Provider data handling still applies to requests it receives.

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
  512 KB (524,288 bytes), even without `Content-Length`. Optional JPEG base64 is
  capped separately at 400 KB (409,600 ASCII characters).
- CORS is off: no access-control headers, no OPTIONS handler, and requests carrying
  `Origin` are rejected. Non-browser clients can still imitate the public tag.
- Each answer is one short model call (at most 200 output tokens), with an
  optional camera frame capped at 768 px on its long side. Check the model's
  current pricing before deploying.

References: [official SDK](https://github.com/anthropics/anthropic-sdk-typescript),
[Haiku model](https://platform.claude.com/docs/en/models/haiku-5-5/overview),
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
