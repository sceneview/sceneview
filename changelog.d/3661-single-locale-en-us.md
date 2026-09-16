<!-- category: Changed -->
- **The demo apps now ship in en-US only
  ([#3661](https://github.com/sceneview/sceneview/pull/3661)).** The apps never had a second
  translation of their own — no `values-<locale>/`, no `.lproj` beyond `Base`, no string
  catalog — but they still rendered half-translated on a non-English phone: AndroidX and
  Material ship their own translations inside their AARs (`appcompat` alone carries 84 locale
  folders), so system-provided strings like "Cancel" appeared in the device language while
  every demo string stayed English. Both native Android demos now set
  `localeFilters += ['en-rUS']`, and the built APK's resource table reports no locale
  qualifier at all. Source copy was also normalized to American spelling — `colour` → `color`,
  `centre` → `center`, `centimetres` → `centimeters`, `Licence` → `License` — across the
  Android, iOS, Flutter and React Native demos. Locale-sensitive *formatting* is untouched
  and still tested: an en-US UI still runs on a French device, where a decimal comma must
  never reach the screen.
