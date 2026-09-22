<!-- category: Fixed -->
- The App Store archive of the iOS demo failed since 4.39.0: `ARExperienceContainer` used the iOS-only `.navigationBar` toolbar placement and `UIAccessibility` in code also compiled for macOS. Both are now guarded (#3768 follow-up).
