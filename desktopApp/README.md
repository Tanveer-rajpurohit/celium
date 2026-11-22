# Celium desktop

Windows prototype version 0.1.0, using the existing Compose/JVM module and a bundled Moviesmod source adaptation. Website work is deferred.

The window implements search, movie details, available series episodes, source extraction/selection, error recovery, and embedded VLC controls. Compilation and successful search do not establish end-to-end Moviesmod playback: its intermediary pages have required additional browser navigation during testing. See [implementation status](../docs/desktop/IMPLEMENTATION.md) for actual outcomes.

## Run from source

Requirements: JDK 17, the repository's Android SDK/build setup, 64-bit VLC 3 installed, and Microsoft Edge for JavaScript link pages. Keep Java and VLC on the same architecture. Browser sessions use a temporary profile, with no access to your personal browser profile. The first dependency resolution needs network access.

From the repository root in PowerShell:

```powershell
.\gradlew.bat :desktopApp:run
```

Search for a title, open it, then choose a movie source or episode. If a temporary Edge window opens, complete any required interaction there. Cancel closes that operation. A failed source keeps the current title/episode context so another source can be selected. The prototype does not install or auto-update providers independently.

To build a local application image with its Java runtime:

```powershell
.\gradlew.bat :desktopApp:createDistributable
```

This does not publish a GitHub release or build an APK. VLC and Edge are prerequisites rather than bundled runtimes; installer distribution is not ready yet.

## Checks

```powershell
.\gradlew.bat :desktopApp:jvmTest
```

Network/browser/decoder checks are opt-in and skipped by default:

```powershell
$env:CELIUM_LIVE_TESTS = 'true'
.\gradlew.bat :desktopApp:jvmTest
Remove-Item Env:CELIUM_LIVE_TESTS
```

These can open a temporary Edge window and fail when Moviesmod or its hosts need interaction, change layout, or are unavailable. The native decoder check uses W3C's public Sintel trailer with dummy audio/video outputs; it does not verify visible embedded playback.

## Code locations

- `src/jvmMain/kotlin/com/lagradost/cloudstream4/Main.kt`: desktop entry point.
- `src/jvmMain/kotlin/`: desktop screens and platform integrations; add feature packages as needed.
- `src/desktop-icons/`: installer icons.
- `build.gradle.kts`: desktop dependencies, application version, and installer configuration.

Reuse compatible code from `shared/` and `library/`. Android `.cs3` files are not loaded by the desktop client. Source provenance, license, and adaptation changes are recorded in [PROVIDERS.md](PROVIDERS.md). The root `windows/` directory launches the Android app through an emulator and is separate from this module.

See [the repository structure](../structure.md) for platform ownership and release requirements.

Before implementing provider loading, read [the provider compatibility findings](../docs/DESKTOP_PROVIDER_COMPATIBILITY.md).
