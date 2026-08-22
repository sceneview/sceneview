<!-- category: Changed -->
The in-AR instructions overlay shared by every AR demo (`DemoStatusBanner`) is now a
dark-scrim coaching pill — white 16 sp text, a leading spinner or severity icon, a
hairline border and a soft lift — instead of a flat brand-coloured capsule with 14 sp
text, which was hard to read over a live camera feed. Passing `null` or a blank string
now animates the pill out, so a demo whose step is done hides it without extra code
(#3265).
