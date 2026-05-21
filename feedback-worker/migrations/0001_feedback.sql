-- In-app user feedback submitted from the SceneView demo apps.
-- The transcript + context are mirrored into a public GitHub issue;
-- the raw media lives in R2 and is purged after the retention window.
CREATE TABLE IF NOT EXISTS feedback (
  id            TEXT    PRIMARY KEY,                              -- uuid v4
  created_at    TEXT    NOT NULL DEFAULT (datetime('now')),       -- server receipt time
  category      TEXT    NOT NULL CHECK (category IN ('bug', 'idea')),
  status        TEXT    NOT NULL DEFAULT 'new'
                        CHECK (status IN ('new', 'issued', 'error', 'purged')),
  transcript    TEXT,                                            -- Whisper output (user's language)
  context_json  TEXT,                                            -- device / OS / app version / demo
  video_key     TEXT,                                            -- R2 key, NULL once purged
  audio_key     TEXT,                                            -- R2 key, NULL once purged
  github_issue  INTEGER,                                         -- created issue number
  github_url    TEXT,                                            -- created issue html_url
  media_purged  INTEGER NOT NULL DEFAULT 0                        -- 1 once R2 media is deleted
);

CREATE INDEX idx_feedback_created ON feedback (created_at);
CREATE INDEX idx_feedback_status  ON feedback (status);
