import type { Env } from "./env.js";

/** Base64-encode an ArrayBuffer (chunked to stay within argument limits). */
function toBase64(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  let bin = "";
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    bin += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  }
  return btoa(bin);
}

export interface Transcription {
  text: string;
  /** Detected language code, when the model reports one. */
  language?: string;
}

/**
 * Transcribe an audio clip with Workers AI (Whisper large-v3-turbo).
 * Language is auto-detected — the user speaks in their own language.
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
    const result = await run("@cf/openai/whisper-large-v3-turbo", {
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
