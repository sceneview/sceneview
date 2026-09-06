<!-- category: Fixed -->
- **An opened file reached the AR placement picker labelled `opened-model`
  ([#3482](https://github.com/sceneview/sceneview/issues/3482)).** The staged copy is deliberately
  named `opened-model` on disk — a display name comes from whichever app shared the file, so
  putting it on a path would mean sanitising untrusted text — and AR derived its row label from
  that path's basename. The user opened `rocket.3mf` and the picker offered them `opened-model`.
  The viewer now carries the file's own name across to placement, next to the real-world size it
  already measured.
