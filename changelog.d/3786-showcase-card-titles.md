<!-- category: Fixed -->

- **iOS demo: Showcase card titles and subtitles wrap instead of being cut off ([#3786](https://github.com/sceneview/sceneview/issues/3786)).** Each card capped both lines at one line, so "Collision & Hit Test", "Geometry Primitives", "AR Placement Reticle" and most subtitles ended in an ellipsis at the default text size. They now wrap without a line cap, as Android's `DemoMediaCard` does, and every card in a row stretches to the row's height so the grid stays aligned.
