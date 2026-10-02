<!-- category: Fixed -->
- **iOS: the camera coast and auto-rotation keep their speed at low frame rates ([#3746](https://github.com/sceneview/sceneview/issues/3746)).** Frames up to 0.25 s now integrate their real elapsed time, while longer frames pause self-driven motion. `maxMotionStep` is now the 0.25 s hitch threshold, matching the `sceneview-web` contract.
