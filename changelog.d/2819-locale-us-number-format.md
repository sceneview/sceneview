- **Demo app:** numbers on the English UI no longer render with the device's decimal
  separator. 61 `String.format` call sites across 21 demo files formatted against the
  device default locale, so a French phone showed `Camera distance: 1,5 m`,
  `Density: 0,25` and `Trajectory 1,80 m` on an otherwise English screen. Every format
  string carrying a locale-sensitive conversion (`%f`, `%e`, `%g`, `%d`) is now pinned to
  `Locale.US`, matching the locale the app's `SimpleDateFormat` sites already used.
  Purely textual `%s` formats are left alone — they have no locale sensitivity. (#2819)

<!-- category: Fixed -->
