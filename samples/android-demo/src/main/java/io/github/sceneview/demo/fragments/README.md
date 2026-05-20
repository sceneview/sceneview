# `io.github.sceneview.demo.fragments` — append-only demo registration

This package holds **one Kotlin file per demo**, plus the `DemoFragment`
interface they implement and the `GeneratedDemos.kt` aggregate emitted by the
collator. Every demo surfaced on the Samples tab, the Explore carousel, and
the deep-link router lives here.

## Why

Before #1797, adding a demo required edits to four or five shared files:

- `DemoRegistry.kt` (the central `ALL_DEMOS` list)
- `MainActivity.kt` (the `DemoRouter` `when` block + the `import` block)
- `strings.xml` (per-demo title/subtitle)
- `llms.txt` and `docs/docs/llms.txt`

Two PRs adding two different demos always conflict on the same anchors, and
merging one created fresh conflicts in every sibling — five+ unnecessary
rebase cycles during the May 2026 ARCore sprint alone.

This package is the fix: **one new demo = one new file**, sorted by id by the
collator so the diff stays stable. Two parallel PRs touch disjoint files.
Zero conflicts. Per-demo strings followed the same pattern in #1870 — each
demo ships its strings in `res/values/strings_demo_<id>.xml`, so `strings.xml`
is no longer a shared anchor either.

## Adding a new demo

1. Drop a new file at `fragments/<MyDemo>Fragment.kt`:

   ```kotlin
   package io.github.sceneview.demo.fragments

   import androidx.compose.material.icons.Icons
   import androidx.compose.material.icons.filled.Star
   import androidx.compose.runtime.Composable
   import io.github.sceneview.demo.DemoCategory
   import io.github.sceneview.demo.DemoEntry
   import io.github.sceneview.demo.R
   import io.github.sceneview.demo.demos.MyDemoComposable

   /** Append-only fragment for the `my-demo` demo. See [DemoFragment]. */
   object MyDemoFragment : DemoFragment {
       override val entry: DemoEntry = DemoEntry(
           id = "my-demo",
           titleRes = R.string.demo_my_demo_title,
           subtitleRes = R.string.demo_my_demo_subtitle,
           category = DemoCategory.BASICS_3D,
           icon = Icons.Filled.Star,
       )

       @Composable
       override fun Screen(onBack: () -> Unit) {
           MyDemoComposable(onBack)
       }
   }
   ```

2. Drop the demo's strings into their own resource fragment at
   `samples/android-demo/src/main/res/values/strings_demo_<my_demo>.xml`
   (substitute `-` with `_` in the id to match Android resource-naming rules).
   Each demo owns its strings file so two parallel PRs adding two different
   demos never share a string-resource anchor (#1870). Android's resource
   merger fans every `res/values/*.xml` in at build time, so the
   `R.string.demo_my_demo_*` references in your fragment resolve identically.
   Minimal template:

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <resources>
       <string name="demo_my_demo_title">My Demo</string>
       <string name="demo_my_demo_subtitle">What it shows in one sentence</string>
   </resources>
   ```

   Any demo-specific UI strings (button labels, picker entries…) belong in
   this file too — they keep the demo self-contained and avoid edits to the
   shared `strings.xml`.

3. Run the collator:

   ```bash
   bash samples/android-demo/scripts/collate-demos.sh
   ```

   This regenerates `GeneratedDemos.kt` from every `*Fragment.kt` in this
   directory, sorted by demo id. Commit the regenerated file alongside your
   fragment.

That's it. No edits to `DemoRegistry.kt`. No edits to `MainActivity.kt`.
No `when` branches, no import block, no list literal — the collator handles
the wiring.

## Rules

- **One fragment per file.** The object is named `<DemoId>Fragment` in
  CamelCase (e.g. `ar-record-playback` → `ArRecordPlaybackFragment`).
- **No shared anchors.** Never edit `GeneratedDemos.kt` by hand — it is
  regenerated. Never add a one-off `when` branch in `MainActivity.kt` — the
  generated `GeneratedDemos.Screen()` is the only routing table.
- **Stable ids.** Once a demo ships, its id is part of the public deep-link
  surface (`sceneview://demo/<id>`). Renaming a fragment file is fine; renaming
  the `id` string breaks every link in the wild.
- **CI check.** `scripts/collate-demos.sh --check` is wired into the pre-push
  quality gate so a fragment added without re-running the collator fails the
  check rather than landing a stale `GeneratedDemos.kt`.
