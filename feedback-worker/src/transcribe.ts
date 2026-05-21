import type { Env } from "./env.js";

/** The Whisper model used. `whisper-large-v3-turbo` takes a base64 string. */
export const WHISPER_MODEL = "@cf/openai/whisper-large-v3-turbo";

/**
 * Base64-encode an ArrayBuffer without a giant argument spread.
 *
 * `String.fromCharCode(...bigArray)` spreads every byte onto the call stack and
 * throws `RangeError: Maximum call stack size exceeded` for a multi-MB audio
 * buffer. This builds the binary string one byte at a time over fixed-size
 * chunks, so a 30 MB clip encodes safely.
 */
function toBase64(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  let bin = "";
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    const end = Math.min(i + CHUNK, bytes.length);
    for (let j = i; j < end; j++) bin += String.fromCharCode(bytes[j]);
  }
  return btoa(bin);
}

export interface Transcription {
  text: string;
  /** Detected language code, when the model reports one. */
  language?: string;
}

/**
 * Transcribe an audio clip with Workers AI.
 *
 * `@cf/openai/whisper-large-v3-turbo` takes the audio as a **base64 string**
 * (the older `@cf/openai/whisper` model is the one that takes a uint8 array —
 * the two contracts are not interchangeable). Language is auto-detected so the
 * user can speak in their own language.
 *
 * Never throws: on failure it returns an empty transcript so the feedback
 * still produces an issue, with the private recording as the fallback signal.
 */
export async function transcribe(
  env: Env,
  audio: ArrayBuffer,
): Promise<Transcription> {
  try {
    const run = env.AI.run as unknown as (
      model: string,
      inputs: unknown,
    ) => Promise<{ text?: string; transcription_info?: { language?: string } }>;
    const result = await run(WHISPER_MODEL, {
      audio: toBase64(audio),
    });
    return {
      text: (result.text ?? "").trim(),
      language: result.transcription_info?.language,
    };
  } catch (e) {
    console.error("transcription failed", e);
    return { text: "" };
  }
}
