<!-- category: Fixed -->
Point & Ask (demo app) no longer answers every tap with the same "Gemini Nano couldn't
answer". The captured AR frame is now cropped around the tap and downscaled to the
on-device model's budget — ML Kit only clamps a bitmap's *short* edge to 768 px, so a
1080×2424 phone capture used to reach Gemini Nano as a 768×1723 strip — and each failure
mode now names itself (unsupported device, stale AICore, busy model, rejected frame,
capture failure, empty answer) instead of collapsing into one string. A failure that
retrying cannot fix retires the "tap to try again" invitation and explains the
on-device-only design instead.
