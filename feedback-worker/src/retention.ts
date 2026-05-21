import type { Env } from "./env.js";

/** Max rows drained per cron tick — a backlog drains incrementally. */
const PURGE_BATCH = 500;

/**
 * Delete R2 media older than 90 days. The feedback row, its transcript and its
 * `status` are left untouched — only the heavy audio/video expires, so the
 * record stays honest about whether the GitHub issue was ever created.
 *
 * Each object is deleted independently and its key column is NULLed per-object,
 * so a single bad/permission-denied key can never strand its sibling. The
 * SELECT is `LIMIT`ed so a large backlog drains over successive cron ticks
 * instead of timing out the worker in one pass.
 *
 * Returns the number of records fully purged (both keys cleared).
 */
export async function purgeExpiredMedia(env: Env): Promise<number> {
  const rows = await env.DB.prepare(
    `SELECT id, video_key, audio_key FROM feedback
     WHERE media_purged = 0
       AND created_at < datetime('now', '-90 days')
     LIMIT ?`,
  )
    .bind(PURGE_BATCH)
    .all<{ id: string; video_key: string | null; audio_key: string | null }>();

  let purged = 0;
  for (const row of rows.results) {
    let videoCleared = !row.video_key;
    let audioCleared = !row.audio_key;

    if (row.video_key) {
      try {
        await env.MEDIA.delete(row.video_key);
        await env.DB.prepare(
          `UPDATE feedback SET video_key = NULL WHERE id = ?`,
        )
          .bind(row.id)
          .run();
        videoCleared = true;
      } catch (e) {
        console.error(`video purge failed for ${row.id}`, e);
      }
    }

    if (row.audio_key) {
      try {
        await env.MEDIA.delete(row.audio_key);
        await env.DB.prepare(
          `UPDATE feedback SET audio_key = NULL WHERE id = ?`,
        )
          .bind(row.id)
          .run();
        audioCleared = true;
      } catch (e) {
        console.error(`audio purge failed for ${row.id}`, e);
      }
    }

    // Only flag the row purged once BOTH objects are gone — otherwise a
    // retry on the next tick still sees the stranded sibling.
    if (videoCleared && audioCleared) {
      try {
        await env.DB.prepare(
          `UPDATE feedback SET media_purged = 1 WHERE id = ?`,
        )
          .bind(row.id)
          .run();
        purged++;
      } catch (e) {
        console.error(`media_purged flag failed for ${row.id}`, e);
      }
    }
  }
  return purged;
}
