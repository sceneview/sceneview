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

/** Escape a value for a single Markdown table cell. */
function cell(value: unknown): string {
  return String(value ?? "")
    .replace(/[\r\n]+/g, " ")
    .replace(/\|/g, "\\|")
    .trim()
    .slice(0, 300);
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

  if (transcript.trim()) {
    lines.push("### What the user said", "");
    for (const l of neutralize(transcript.trim()).split("\n")) {
      lines.push(`> ${l}`);
    }
    lines.push("");
  }

  if (text.trim()) {
    lines.push("### Description", "", neutralize(text.trim()), "");
  }

  if (!transcript.trim() && !text.trim()) {
    lines.push(
      "_No transcript available — see the recording in the viewer below._",
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
