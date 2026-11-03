# Celium desktop

The existing Compose Multiplatform/JVM module is the starting point for the native desktop app. Its current window contains demo controls; browsing, playback, and downloads still need implementation.

- `src/jvmMain/kotlin/com/lagradost/cloudstream4/Main.kt`: desktop entry point.
- `src/jvmMain/kotlin/`: desktop screens and platform integrations; add feature packages as needed.
- `src/desktop-icons/`: installer icons.
- `build.gradle.kts`: desktop dependencies, application version, and installer configuration.

Reuse compatible code from `shared/` and `library/`. Android `.cs3` plugin compatibility must be verified rather than assumed. The root `windows/` directory launches the Android app through an emulator and is separate from this module.

See [the repository structure](../structure.md) for platform ownership and release requirements.
