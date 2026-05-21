import { describe, it, expect } from "vitest";
import { transcribe, WHISPER_MODEL } from "../src/transcribe.js";
import type { Env } from "../src/env.js";

/**
 * Build a minimal Env whose AI.run records the model + inputs it was called
 * with, so a test can assert the exact contract handed to Workers AI.
 */
function captureEnv(result: unknown): {
  env: Env;
  calls: { model: string; inputs: unknown }[];
} {
  const calls: { model: string; inputs: unknown }[] = [];
  const env = {
    AI: {
      run: async (model: string, inputs: unknown) => {
        calls.push({ model, inputs });
        return result;
      },
    },
  } as unknown as Env;
  return { env, calls };
}

describe("transcribe", () => {
  it("calls whisper-large-v3-turbo with the audio as a base64 STRING", async () => {
    const { env, calls } = captureEnv({
      text: "the spinner never stops",
      transcription_info: { language: "en" },
    });
    const audio = new Uint8Array([1, 2, 3, 4, 5]).buffer;
    const out = await transcribe(env, audio);

    expect(calls).toHaveLength(1);
    expect(calls[0].model).toBe(WHISPER_MODEL);
    expect(calls[0].model).toBe("@cf/openai/whisper-large-v3-turbo");

    // The large-v3-turbo model contract expects a base64 string, NOT a
    // uint8 array — the two are not interchangeable.
    const inputs = calls[0].inputs as { audio: unknown };
    expect(typeof inputs.audio).toBe("string");
    expect(Array.isArray(inputs.audio)).toBe(false);
    // Round-trips back to the original bytes.
    const decoded = Uint8Array.from(atob(inputs.audio as string), (ch) =>
      ch.charCodeAt(0),
    );
    expect([...decoded]).toEqual([1, 2, 3, 4, 5]);

    expect(out.text).toBe("the spinner never stops");
    expect(out.language).toBe("en");
  });

  it("base64-encodes a multi-MB buffer without a call-stack overflow", async () => {
    const { env, calls } = captureEnv({ text: "" });
    // 5 MB — a naive String.fromCharCode(...spread) would RangeError here.
    const big = new Uint8Array(5 * 1024 * 1024);
    await expect(transcribe(env, big.buffer)).resolves.toBeDefined();
    expect(typeof (calls[0].inputs as { audio: unknown }).audio).toBe(
      "string",
    );
  });

  it("returns an empty transcript (never throws) when AI.run fails", async () => {
    const env = {
      AI: {
        run: async () => {
          throw new Error("Workers AI unavailable");
        },
      },
    } as unknown as Env;
    const out = await transcribe(env, new Uint8Array([1]).buffer);
    expect(out.text).toBe("");
  });
});
