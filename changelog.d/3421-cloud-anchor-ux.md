<!-- category: Changed -->
Rebuilt the demo app's Cloud Anchors screen as an explicit two-step flow — Host an anchor
(place, map the room, upload, share the code) and Resolve one (paste a code, resolve, see
the anchor). The host/resolve state machine moved into plain, unit-tested Kotlin, so a
running request now owns the status line instead of being shadowed by coaching copy,
severity comes from the state rather than from substring-matching the sentence, and no
action is offered that cannot work — Host waits for a placed anchor and a sufficient
ARCore `FeatureMapQuality`, which is rendered as a room-mapping meter. Failures are
explained in the app's own words instead of printing the raw ARCore constant, hosted codes
can be copied or shared through the system share sheet and pasted back from the clipboard,
and a missing or rejected ARCore Cloud API key now gets the same explanation card as an
unavailable ARCore session. The screen's own chrome uses the AR overlay tokens, so it
reads identically in light and dark over a camera feed.
