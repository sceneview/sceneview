export type Category = "bug" | "idea";

export type FeedbackContext = Record<
  string,
  string | number | boolean | null | undefined
>;

/**
 * Neutralize @mentions and #refs in user text so a transcript can never ping a
 * GitHub user or cross-link an unrelated issue. Inserts a zero-width space
 * (U+200B) — visually identical, but breaks GitHub's autolink pattern.
 */
function neutralize(text: string): string {
  return text.replace(/([@#])(?=[\w-])/g, "$1\u200b");
}

/** Escape every Markdown-significant character in an inline string. */
function escapeMarkdown(s: string): string {
  return s.replace(/[\\`*_{}[\]()#+\-.!|~<>]/g, "\\$&");
}

/**
 * Escape a value for a single Markdown table cell. The context values are
 * client-controlled, so all Markdown specials are escaped — not just `|` —
 * to stop link/image injection through the context table.
 */
function cell(value: unknown): string {
  return escapeMarkdown(
    String(value ?? "")
      .replace(/[\r\n]+/g, " ")
      .trim()
      .slice(0, 300),
  );
}

/**
 * Render an untrusted free-text block (the user's typed description or the
 * Whisper transcript) inside a fenced code block. A code fence renders the
 * content verbatim, so `![img](url)` tracking pixels and `[txt](url)` phishing
 * links can never auto-load or become clickable on the public issue tracker.
 *
 * Breakout guard: any run of three-or-more backticks in the content is broken
 * with a zero-width space so it cannot terminate the fence early and escape
 * back into Markdown rendering. The fence itself uses a 4-backtick delimiter.
 */
function fencedBlock(text: string): string {
  const safe = text.replace(/`{3,}/g, (run) => run.split("").join("​"));
  return ["````text", safe, "````"].join("\n");
}

/** Friendly labels + stable display order for known context keys. */
const CONTEXT_LABELS: Record<string, string> = {
  appVersion: "App version",
  appVersionCode: "App build",
  androidVersion: "Android",
  sdkInt: "API level",
  device: "Device",
  demoId: "Demo",
  demoTitle: "Demo title",
  locale: "Locale",
  freeRamMb: "Free RAM (MB)",
  transcriptLanguage: "Transcript language",
};

const MAX_BODY = 60_000;

export interface BuiltIssue {
  title: string;
  body: string;
  labels: string[];
}

/** Build the GitHub issue title/body/labels from a feedback submission. */
export function buildIssue(input: {
  id: string;
  category: Category;
  transcript: string;
  text: string;
  context: FeedbackContext;
  viewerUrl: string;
}): BuiltIssue {
  const { id, category, transcript, text, context, viewerUrl } = input;

  const tag = category === "bug" ? "Bug" : "Idea";
  const summary =
    text.trim() ||
    transcript.trim().split(/[.\n]/)[0]?.trim() ||
    "User feedback";
  // neutralize() so an @mention / #ref in the summary cannot ping a user or
  // cross-link an unrelated issue from the title.
  const title = `[${tag}] ${neutralize(summary)}`
    .replace(/\s+/g, " ")
    .slice(0, 110);

  const lines: string[] = [
    `> Submitted via the in-app feedback reporter (${category}).`,
    "",
  ];

  // The transcript and the typed text are untrusted free text. They are
  // rendered inside fenced code blocks so a `![img](url)` tracking pixel can
  // never auto-load and a `[txt](url)` phishing link can never become
  // clickable on the public sceneview/sceneview tracker. neutralize() still
  // breaks @mentions / #refs that would otherwise show literally.
  if (transcript.trim()) {
    lines.push(
      "### What the user said",
      "",
      fencedBlock(neutralize(transcript.trim())),
      "",
    );
  }

  if (text.trim()) {
    lines.push(
      "### Description",
      "",
      fencedBlock(neutralize(text.trim())),
      "",
    );
  }

  if (!transcript.trim() && !text.trim()) {
    // Provide context about WHY there is no content (#2123).
    // If the `isEmulator` context flag is set, the mic was definitively silent
    // (emulators have no physical microphone — Whisper returned nothing).
    // Otherwise the recording may have captured silence for another reason
    // (mic permission denied, very quiet room, etc.).
    const isEmulator = String(context["isEmulator"]) === "true";
    const noContentReason = isEmulator
      ? "_No transcript — submitted from an emulator (no physical mic). Whisper received a silent audio track._"
      : "_No transcript available and no typed description — Whisper may have received a silent audio track, or no audio was captured._";
    lines.push(
      noContentReason,
      "",
      "> **Note for maintainers:** there is no actionable content in this submission. " +
        "The recording (if any) is linked below — check it before closing.",
      "",
    );
  }

  // Context table — known keys first (stable order), then any extras.
  const rows: string[] = [];
  const seen = new Set<string>();
  for (const key of Object.keys(CONTEXT_LABELS)) {
    const v = context[key];
    if (v !== undefined && v !== null && String(v) !== "") {
      rows.push(`| ${CONTEXT_LABELS[key]} | ${cell(v)} |`);
      seen.add(key);
    }
  }
  for (const [key, v] of Object.entries(context)) {
    if (seen.has(key)) continue;
    if (v !== undefined && v !== null && String(v) !== "") {
      rows.push(`| ${cell(key)} | ${cell(v)} |`);
    }
  }
  if (rows.length) {
    lines.push(
      "### Context",
      "",
      "| Field | Value |",
      "| --- | --- |",
      ...rows,
      "",
    );
  }

  lines.push(
    `🎬 **[Screen recording + audio — maintainers only](${viewerUrl})**`,
    "",
    `<sub>Feedback ID: \`${id}\`</sub>`,
  );

  const body = lines.join("\n").slice(0, MAX_BODY);
  const labels = ["user-feedback", category === "bug" ? "bug" : "enhancement"];

  return { title, body, labels };
}
