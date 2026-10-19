# Celium 6 verification
Date: 18 October 2026. Version 6.0.0-celium (70), application ID app.celium.android.debug.

## Celium 6 build results
Command (JDK 17, Windows):
```powershell
.\gradlew.bat :app:assembleStableDebug :app:testStableDebugUnitTest --no-configuration-cache --max-workers=2 --console=plain
```

| Check | Result |
| --- | --- |
| Android assembly / Kotlin compilation | BUILD SUCCESSFUL (exit 0) |
| Unit tests | 30 passed, 0 failed, 0 skipped (CeliumPolicyTest 14, SubtitleLanguageTagTest 8, SearchRankingTest 5, AppProxyTest 3) |
| APK versionName / versionCode | 6.0.0-celium / 70 (aapt2 dump badging, package app.celium.android.debug) |
| APK SHA-256 | `898A4F5CE853283AAB5B388ABB82AEC408B185E28D655CB0C6060F49BF83B701` |

The APK (`app/build/outputs/apk/stable/debug/app-stable-debug.apk`) was copied as `celium-6.0.0.apk` and `windows/celium.apk` in the delivery folder; both copies match the hash above.

## Sources
23 selected artifacts. The 18 carried forward from Celium 5 keep their earlier digest and manifest/classes.dex inspection. The 5 additions (AniKoto, HiAnime, Movies4u from Phisher; Moviesmod from the CSX repo; XNXX from CXXX) record version and SHA-256 from live repo json on 18 October 2026. Their packages were not downloaded or inspected for this document.

AnimePahe and Porn36 are excluded. Adult defaults OFF. All sources depend on external providers.

## What was not verified
Celium 6 has not been verified on a device or emulator. These are NOT established by any passing build:
- Visual quality, Light default, accent picker, quick-return header and infinite scroll on a real screen.
- Live playback, source switching, link fallback, quality selection and subtitles for any source.
- Player downloads, queue visibility and notification behavior.
- Scrolling frame times, startup/search latency, memory use or download throughput.
- Zero adult network traffic on an instrumented device, beyond the tested policy gates.
- Windows emulator cold boot, installation and repeat-start behavior.

## Celium 5 baseline
Celium 5 (5.0.0-celium, 69) built successfully with 29 unit tests passing, lint 0 errors / 524 warnings, and 18/18 retained digests matched. Those results do not carry over to Celium 6.

## Remaining boundaries
Some inherited engine classes, resources and dialogs remain because of shared references and extension compatibility. Windows delivery runs Android in an existing emulator; no native Windows .cs3 runtime, bundled SDK/AVD or built-in VPN. The debug signing key is not in the source ZIP.
