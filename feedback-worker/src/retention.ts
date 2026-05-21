import type { Env } from "./env.js";

/**
 * Delete R2 media older than 90 days. The feedback row, its transcript and its
 * `status` are left untouched — only the heavy audio/video expires, so the
 * record stays honest about whether the GitHub issue was ever created.
 * Returns the number of records purged.
 */
export async function purgeExpiredMedia(env: Env): Promise<number> {
  const rows = await env.DB.prepare(
    `SELECT id, video_key, audio_key FROM feedback
     WHERE media_purged = 0
       AND created_at < datetime('now', '-90 days')`,
  ).all<{ id: string; video_key: string | null; audio_key: string | null }>();

  let purged = 0;
  for (const row of rows.results) {
    try {
      if (row.video_key) await env.MEDIA.delete(row.video_key);
      if (row.audio_key) await env.MEDIA.delete(row.audio_key);
      await env.DB.prepare(
        `UPDATE feedback
         SET media_purged = 1, video_key = NULL, audio_key = NULL
         WHERE id = ?`,
      )
        .bind(row.id)
        .run();
      purged++;
    } catch (e) {
      console.error(`media purge failed for ${row.id}`, e);
    }
  }
  return purged;
}
