import type { Env } from "./env.js";

/** A row of the `feedback` table. */
export interface FeedbackRow {
  id: string;
  created_at: string;
  category: "bug" | "idea";
  status: string;
  transcript: string | null;
  context_json: string | null;
  video_key: string | null;
  audio_key: string | null;
  github_issue: number | null;
  github_url: string | null;
  media_purged: number;
}

export async function insertFeedback(
  env: Env,
  row: {
    id: string;
    category: string;
    transcript: string | null;
    context_json: string;
    video_key: string | null;
    audio_key: string | null;
  },
): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO feedback (id, category, status, transcript, context_json, video_key, audio_key)
     VALUES (?, ?, 'new', ?, ?, ?, ?)`,
  )
    .bind(
      row.id,
      row.category,
      row.transcript,
      row.context_json,
      row.video_key,
      row.audio_key,
    )
    .run();
}

export async function markIssued(
  env: Env,
  id: string,
  issueNumber: number,
  issueUrl: string,
): Promise<void> {
  await env.DB.prepare(
    `UPDATE feedback SET status = 'issued', github_issue = ?, github_url = ? WHERE id = ?`,
  )
    .bind(issueNumber, issueUrl, id)
    .run();
}

export async function markError(env: Env, id: string): Promise<void> {
  await env.DB.prepare(`UPDATE feedback SET status = 'error' WHERE id = ?`)
    .bind(id)
    .run();
}

export async function getFeedback(
  env: Env,
  id: string,
): Promise<FeedbackRow | null> {
  return env.DB.prepare(`SELECT * FROM feedback WHERE id = ?`)
    .bind(id)
    .first<FeedbackRow>();
}
