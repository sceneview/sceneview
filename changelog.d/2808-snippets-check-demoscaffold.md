- **CI:** unbreak `main`. The `:snippets-check` module added by #2808 compiles every
  ` ```kotlin ` block in `llms.txt`, but the `DemoScaffold` signature listing added by
  #2780 references `DemoBottomOverlayScope` — a type that lives in `samples/android-demo`,
  which is deliberately not on `:snippets-check`'s classpath (the module depends on the
  libraries, not on the sample app). Every PR opened since has been red on
  `Build libraries & samples` through no fault of its own. The block is now tagged
  ` ```kotlin notest <reason> `, the escape hatch the extractor documents for exactly this
  case. (#2808)

<!-- category: Fixed -->
