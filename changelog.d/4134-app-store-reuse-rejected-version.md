<!-- category: Fixed -->
- **CI (App Store):** an iOS version withdrawn from review or rejected by Apple no longer blocks every later release. The submit step now reuses that editable record for the new version (and the screenshot sync writes to it), instead of 409-ing on a new record and deferring forever.
