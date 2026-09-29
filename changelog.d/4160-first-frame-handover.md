<!-- category: Fixed -->
- **Demo app**: the loading cover now hands over to a demo's first rendered frame in 150 ms instead of 350 ms (new `motion-handover` token). Tracing showed the cover already lifts within ~30 ms of Filament finishing frame 1; the remaining wait on the emulator is frame 1's first shader compile, not the cover. (#4160)
