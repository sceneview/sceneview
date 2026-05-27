-- Issue #2208 — better client identification + bot detection.
--
-- Two new columns:
--   install_id      — opaque per-install UUID emitted by the sender. Lets us
--                     count UNIQUE developers per `client` runtime, where the
--                     current `client = 'claude-code'` collapses everyone into
--                     a single bucket. NULL for events from older SDKs.
--   bot_likelihood  — sender-side heuristic in [0.0, 1.0]. >=0.7 is treated as
--                     a bot by default in /v1/stats, /v1/funnel, /v1/top-clients
--                     (toggle with ?include_bots=1). NULL events are treated
--                     as non-bot for backward compatibility.
--
-- Both columns are nullable so events from pre-2208 SDKs continue to be
-- accepted and stored without changes.

ALTER TABLE events ADD COLUMN install_id     TEXT;
ALTER TABLE events ADD COLUMN bot_likelihood REAL;

-- Index for the "unique developers per client" rollup queries.
CREATE INDEX IF NOT EXISTS idx_events_install_id ON events (install_id)
  WHERE install_id IS NOT NULL;

-- Composite index speeds up the default bot-exclusion filter on stats endpoints.
CREATE INDEX IF NOT EXISTS idx_events_bot_ingested
  ON events (bot_likelihood, ingested);
