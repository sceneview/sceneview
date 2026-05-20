<!-- category: Added -->
- `arsceneview`: rounded out the ARCore recording/playback surface (#1770).
  - `rememberARPlaybackStatus(session): State<PlaybackStatus>` — Compose State that surfaces `NONE` / `OK` / `FINISHED` / `IO_ERROR` (the **`FINISHED`** transition is the only public end-of-replay signal, useful for rewind / loop / next-dataset logic).
  - `ARRecorder.State.IO_ERROR` — distinct from generic `ERROR`. Set by `recordFrame(session)` when ARCore reports `RecordingStatus.IO_ERROR` (disk full, storage detached, permission revoked mid-recording) so apps can offer a "clear cache and retry" CTA.
  - `ARRecorder.addTrack(uuid, mimeType)` + `ARRecorder.recordTrack(handle, frame, data)` — exposes ARCore's `RecordingConfig.addTrack` + `Frame.recordTrackData` flow for ML annotation / ground-truth / custom sensor packets written inside the same MP4.
  - `ARSceneView(playbackDatasetUri: Uri? = null)` — scoped-storage equivalent of the `playbackDataset: File?` param (Android 10+). Accepts `content://` URIs straight from the SAF picker so apps don't have to copy into app-private storage. Mutually exclusive with `playbackDataset` — setting both throws `IllegalArgumentException`.
