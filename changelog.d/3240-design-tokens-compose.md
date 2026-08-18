<!-- category: Changed -->
<!-- breaking: false -->
The Android demo app now reads its spacing, radius, motion and layout constants from a
single `SceneViewTokens` object that mirrors `DESIGN.md` token-for-token, instead of
repeating the numbers as literals. Two Material shape roles move as a result, because
`Shape.kt` claimed to follow `DESIGN.md` while using values it never defined: the `large`
role goes from 28dp to 24dp (`radius-lg`) and `extraLarge` from 32dp to 28dp
(`radius-xl`). Cards, bottom sheets, buttons and chips in the demo app render with
slightly tighter corners; nothing outside the demo app changes.
