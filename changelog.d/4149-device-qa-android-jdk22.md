<!-- category: Fixed -->
- **CI (Device QA):** the Android Maestro leg now sets up JDK 22. It rebuilds a keyed demo APK on the emulator runner, and without the toolchain Gradle failed before the first flow (`Cannot find a Java installation … languageVersion=22`). The leg is advisory, so those runs still showed green.
