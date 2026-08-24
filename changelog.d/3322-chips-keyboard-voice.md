<!-- category: Fixed -->
Four demo-app polish fixes from Pixel 9 QA (#3322):

- The Lighting Lab mode selector's "Environment" segment wrapped onto two lines on a
  phone-width screen — the segmented buttons no longer reserve space for a selection
  icon (selection is already shown by color), so all four labels fit on one line.
- Custom Geometry's shape picker matched its chips against a raw `String` used as both
  the display label and the dispatch key into three separately hand-copied maps — a
  fragile pattern that a future rename or localization pass would silently break.
  Replaced with an internal `Shape` enum.
- The bug-report sheet's note field could end up hidden behind the keyboard with no way
  to scroll it into view; the sheet now applies `imePadding()` and brings the field into
  view on focus.
- Voice dictation (bug-report note and Point & Ask's question field) cut off after a
  short pause — both now request longer silence-detection windows from the system
  speech recognizer so a normal pause for breath doesn't end the recording early.

"Revoir les bounding box" from the same feedback batch is out of scope here and tracked
separately.
