<!-- category: Fixed -->
- **AR replay QA runs on the arm64 emulator again.** `setup-ar-emulator.sh` boots the QA emulator with an `emulated` back camera instead of `virtualscene`, which emulator 37.x no longer accepts. ARCore now finds camera id 0 and replays the bundled recording: 20 of 25 AR demos replay real frames, where every demo used to stay at 0 frames (#1645).
