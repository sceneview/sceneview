import type { Env } from "./env.js";

/** Media older than this is purged from R2; the D1 transcript is kept. */
const RETENTION_DAYS = 90;

/**
 * Delete R2 media past the retention window. The feedback row and its
 * transcript are kept forever — only the heavy audio/video expires.
 * Returns the number of records purged.
 */
export async function purgeExpiredMedia(env: Env): Promise<number> {
  const rows = await env.DB.prepare(
    `SELECT id, video_key, audio_key FROM feedback
     WHERE media_purged = 0
       AND created_at < datetime('now', '-' || ? || ' days')`,
  )
    .bind(RETENTION_DAYS)
    .all<{ id: string; video_key: string | null; audio_key: string | null }>();

  let purged = 0;
  for (const row of rows.results) {
    try {
      if (row.video_key) await env.MEDIA.delete(row.video_key);
      if (row.audio_key) await env.MEDIA.delete(row.audio_key);
      await env.DB.prepare(
        `UPDATE feedback
         SET media_purged = 1, video_key = NULL, audio_key = NULL,
             status = CASE WHEN status = 'error' THEN status ELSE 'purged' END
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
