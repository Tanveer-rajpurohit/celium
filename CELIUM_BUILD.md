# Build and package Celium 6

Use JDK 17 and the SDK versions pinned in gradle/libs.versions.toml (compile API 37 and build tools 36.0.0 in this delivery). Minimum Android API is 23. Set JAVA_HOME and ANDROID_HOME for your own machine.

If you extracted the source ZIP, run git init in the celium folder once because the inherited metadata task expects a Git directory. A commit, remote and push are not needed.

```powershell
.\gradlew.bat :app:assembleStableDebug :app:testStableDebugUnitTest :app:lintStableDebug --no-configuration-cache --max-workers=2 --console=plain
```

The APK is app/build/outputs/apk/stable/debug/app-stable-debug.apk. This Android build uses application ID app.celium.android.debug and version 6.0.0-celium (70). Test and lint reports are under app/build/reports/.

This is a debug-signed test build. Keep your signing key consistent between your own updates. No signing key or local SDK is included in the source archive.

Celium 6 adds: Light default, plugin popup guard, infinite scroll, quick-return header, random button, accent colour picker, details selectors/recommendations/icon back/header image, live source switching, link fallback and highest quality, player download button, download queue visibility, no phantom download notification, and the full 23-source catalogue.

## Source packaging
Archive the whole celium folder from its parent, keeping one celium root directory. Exclude .git, .gradle, .kotlin, .serena, all build directories, local.properties, keystores, caches and credentials. Keep the Gradle wrapper, source, resources, docs, licenses and Windows starter scripts. Do not include generated APKs in the source-only ZIP.

## Windows starter
Copy your built APK to windows/celium.apk and configure windows/celium-windows.json for an existing SDK and AVD. See windows/README.md. The separate delivery Windows ZIP already includes the APK. Emulator/SDK prerequisites are not bundled.

## Runtime map
- Source policy: ui/celium/CeliumSources.kt; 23 artifacts (Celium 6 added AniKoto, HiAnime, Movies4u, Moviesmod from the CSX repo, XNXX), AnimePahe and Porn36 excluded. Repositories: Phisher, CXXX, GizliKeyif, CNCVerse, CSX.
- Feed/search/cache/cancellation: CeliumViewModel.kt.
- New consumer screens: CeliumScreen, CeliumDetailsFragment, CeliumLibraryFragment, CeliumDownloadsFragment and CeliumSettingsFragment.
- Typography/theme: CeliumTheme; DM Sans license is included in docs and the APK raw resources.
- Viewing ledger: CeliumHistory, separate from existing resume state.
- Domain/compatibility: existing MainAPI, APIRepository, ResultViewModel2, extractors, player and transfer engine.

Adult defaults off. Internal source downloads, updates and loading are gated by policy; ordinary UI does not expose repository setup. First launch requires network access to prepare enabled sources.

Read [current status](CELIUM_STATUS.md), [implementation boundaries](docs/CELIUM_5_CHANGES.md) and [verification](VERIFICATION.md). Real playback, throughput, scrolling, accessibility and Windows emulator behavior still need device tests.
