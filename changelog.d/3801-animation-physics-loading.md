<!-- category: Fixed -->

- **Android demo: the Animation & Physics clip card no longer covers the back button while the character loads ([#3801](https://github.com/sceneview/sceneview/issues/3801)).**
  - Every demo's top overlay now sits below the back button and title pill from the first frame. It used to wait one frame for the row's measured height, and a screen that blocks on a model load kept that first frame on screen for seconds.
  - While the character loads, the card reads "Animation clip · Loading…" with an indeterminate bar instead of "No animation clip available". That message is kept for a model that really has no clip.
