import type { FeedbackRow } from "./db.js";

function esc(s: unknown): string {
  return String(s ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function shell(title: string, inner: string): string {
  return `<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<meta name="robots" content="noindex"/>
<title>${esc(title)}</title>
<style>
  *,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
  body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
       background:#0d1117;color:#e6edf3;min-height:100vh;padding:2rem 1rem}
  .wrap{max-width:680px;margin:0 auto}
  h1{font-size:1.3rem;font-weight:600}
  h2{font-size:.95rem;margin-bottom:.6rem}
  .muted{color:#8b949e;font-size:.85rem;line-height:1.6}
  .badge{display:inline-block;font-size:.7rem;font-weight:600;text-transform:uppercase;
         letter-spacing:.04em;padding:.2rem .55rem;border-radius:20px;margin:.5rem 0 .25rem}
  .badge.bug{background:#3d1418;color:#ff7b72}
  .badge.idea{background:#1a2f1a;color:#3fb950}
  .card,.gate{background:#161b22;border:1px solid #30363d;border-radius:12px;
       padding:1.25rem 1.5rem;margin-top:1rem}
  blockquote{border-left:3px solid #30363d;padding-left:.9rem;color:#c9d1d9;
       white-space:pre-wrap;line-height:1.65}
  table{width:100%;border-collapse:collapse;font-size:.85rem}
  td{padding:.35rem .5rem;border-bottom:1px solid #21262d;vertical-align:top}
  td:first-child{color:#8b949e;white-space:nowrap;width:38%}
  video,audio{width:100%;margin-top:.6rem;border-radius:8px}
  a{color:#4493f8}
  input{background:#0d1117;border:1px solid #30363d;color:#e6edf3;border-radius:6px;
       padding:.45rem .6rem;font-size:.85rem;width:210px}
  button{background:#238636;color:#fff;border:0;border-radius:6px;padding:.5rem 1rem;
       font-size:.85rem;font-weight:600;cursor:pointer;margin-left:.4rem}
</style></head><body><div class="wrap">${inner}</div></body></html>`;
}

export function renderNotFound(): string {
  return shell(
    "Feedback not found",
    `<h1>Feedback not found</h1><p class="muted" style="margin-top:.5rem">This feedback id does not exist, or its record was removed.</p>`,
  );
}

/**
 * Render the feedback viewer page.
 * @param admin true when the request carried a valid admin session cookie —
 *   only then are the screen recording + audio players embedded.
 */
export function renderViewer(row: FeedbackRow, admin: boolean): string {
  let context: Record<string, unknown> = {};
  try {
    context = row.context_json ? JSON.parse(row.context_json) : {};
  } catch {
    context = {};
  }

  const ctxRows = Object.entries(context)
    .filter(([, v]) => v !== undefined && v !== null && String(v) !== "")
    .map(([k, v]) => `<tr><td>${esc(k)}</td><td>${esc(v)}</td></tr>`)
    .join("");
  const ctxCard = ctxRows
    ? `<div class="card"><h2>Context</h2><table>${ctxRows}</table></div>`
    : "";

  const transcriptCard = row.transcript?.trim()
    ? `<div class="card"><h2>What the user said</h2><blockquote>${esc(
        row.transcript.trim(),
      )}</blockquote></div>`
    : `<div class="card"><h2>What the user said</h2><p class="muted">No transcript — see the recording.</p></div>`;

  const issueLink = row.github_url
    ? `<p class="muted" style="margin-top:1rem">GitHub issue: <a href="${esc(
        row.github_url,
      )}">#${esc(row.github_issue)}</a></p>`
    : "";

  const hasMedia = !!(row.video_key || row.audio_key);
  let mediaCard: string;
  if (row.media_purged) {
    mediaCard = `<div class="card"><h2>Recording</h2><p class="muted">Media expired and was purged after the 90-day retention window.</p></div>`;
  } else if (!hasMedia) {
    mediaCard = `<div class="card"><h2>Recording</h2><p class="muted">No screen recording or audio was attached to this feedback.</p></div>`;
  } else if (admin) {
    // Media is fetched with the admin session cookie (Path=/feedback) — no
    // token in the URL.
    const video = row.video_key
      ? `<video controls preload="metadata" src="/feedback/${row.id}/media/video"></video>`
      : "";
    const audio = row.audio_key
      ? `<audio controls preload="metadata" src="/feedback/${row.id}/media/audio"></audio>`
      : "";
    mediaCard = `<div class="card"><h2>Recording</h2>${video}${audio}</div>`;
  } else {
    mediaCard = `<div class="gate"><h2>Recording — maintainers only</h2>
      <p class="muted" style="margin:.4rem 0 .8rem">The screen recording and audio are private. Enter the admin token to play them.</p>
      <form method="get"><input type="password" name="token" placeholder="admin token" autocomplete="off"/><button type="submit">Unlock</button></form></div>`;
  }

  const inner = `<h1>Feedback</h1>
    <span class="badge ${row.category}">${esc(row.category)}</span>
    <p class="muted">Received ${esc(row.created_at)} UTC · status: ${esc(
      row.status,
    )}</p>
    ${transcriptCard}
    ${ctxCard}
    ${mediaCard}
    ${issueLink}`;

  return shell(`Feedback ${row.id}`, inner);
}
