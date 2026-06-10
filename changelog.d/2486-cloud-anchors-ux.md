<!-- category: Fixed -->
- Cloud Anchors demo: made the host→resolve flow discoverable (#2486). The on-screen
  Host/Resolve buttons no longer render as a faint, greyed-out ghost over the camera
  feed — both stay solid and tappable, guiding the next step on-screen (place an anchor,
  enter an ID) instead of being disabled and reading as "there are no buttons". The
  one-line instruction and the Cloud Anchor ID field are now on the main screen rather
  than buried in the Settings sheet, and the status/error banner moved to the top so the
  long `ERROR_NOT_AUTHORIZED` message is no longer clipped behind the buttons. (The
  underlying provisioning failure is tracked in #1436.)
