# Build and package Celium 7

Use JDK 17 and the SDK versions pinned in gradle/libs.versions.toml (compile API 37, build tools 36.0.0). Minimum Android API is 23. Set JAVA_HOME and ANDROID_HOME for your machine.

If you extracted the source ZIP, run git init in the celium folder once because the inherited metadata task expects a Git directory.

```powershell
.\gradlew.bat :app:assembleStableDebug :app:testStableDebugUnitTest --no-configuration-cache --max-workers=2 --console=plain
```

The APK is app/build/outputs/apk/stable/debug/app-stable-debug.apk, application ID app.celium.android.debug, version 7.0.0-celium (71). Test reports are under app/build/reports/.

This is a debug-signed test build. Keep your signing key consistent between updates. No signing key or local SDK is included in the source archive.

## Source packaging
Archive the whole celium folder from its parent, keeping one celium root directory. Exclude .git, .gradle, .kotlin, build directories, local.properties, keystores, caches and credentials. Keep the Gradle wrapper, source, resources, docs, licenses and Windows starter scripts. Do not include APKs in the source-only ZIP.

## Windows package
The PC zip contains the APK, Start Celium.cmd, Start-Celium.ps1, celium-windows.json, bin\Celium.cmd and a README. Configure celium-windows.json for an existing SDK and AVD; see windows/README.md. Emulator/SDK prerequisites are not bundled.

## Runtime map
- Source policy: ui/celium/CeliumSources.kt; 24 sources from the Phisher, CXXX, GizliKeyif, CNC and CSX repositories.
- Feed, priority, cache, classification and cancellation: CeliumViewModel.kt and related ui/celium helpers.
- Screens: CeliumScreen, CeliumDetailsFragment, CeliumLibraryFragment, CeliumDownloadsFragment, CeliumSettingsFragment.
- Theme, layout size and system bars: CeliumTheme; DM Sans license is in docs and the APK raw resources.
- One-time defaults and Celium 7 migration: PersonalDefaults.kt.
- Compatibility layer: existing MainAPI, APIRepository, ResultViewModel2, extractors, player and transfer engine.

Adult defaults off. First launch requires network access to prepare enabled sources.

Read [status](CELIUM_STATUS.md), [changes](docs/CELIUM_CHANGES.md) and [verification](VERIFICATION.md). Playback, scrolling, accessibility and Windows emulator behaviour still need device tests.
