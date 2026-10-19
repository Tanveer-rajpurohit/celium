# Celium 5 verification
Date: 18 October 2026. This is an installable Android test build with source and a Windows emulator starter.

## Build and automated checks
Final packaging command:
```powershell
.\gradlew.bat :app:assembleStableDebug :app:testStableDebugUnitTest :app:lintStableDebug --offline --no-configuration-cache --max-workers=2 --console=plain
```

Result: BUILD SUCCESSFUL in 6m 14s. The packaging state included the font license, history/privacy changes and accessible custom-accent handling.

| Check | Result |
| --- | --- |
| Android assembly / Kotlin compilation | PASS |
| Unit tests | 29 passed; 0 failures; 0 errors |
| Source policy tests | 13 passed, including adult opt-out and curated duplicate/remake behavior |
| Search ranking tests | 5 passed |
| Proxy configuration tests | 3 passed |
| Subtitle language tests | 8 passed |
| Android lint | 0 errors, 524 warnings, 2 hints |
| APK signatures | v1 and v2 verified; one signer |
| Retained extension SHA-256 checks | 18/18 matched the selected manifest |
| Packaged navigation | Home, Search, Details (phone/wide), Library, Downloads and Settings point to Celium screens |
| Offline font / license inside APK | Both present |
| Windows launcher syntax | PASS |
| Windows missing-SDK validation | PASS: clear expected configuration error without installation |

The older baseline recorded 519 lint warnings. Current warnings include inherited issues and new low-severity style/resource warnings; this is not a lint-clean codebase.

## APK identity
- Version: 5.0.0-celium (69)
- Application ID: app.celium.android.debug
- Launcher label: Celium
- Minimum Android API: 23
- SHA-256: 4D33A56249031203360CB71ACDFAB3E99D3D82CB7905584483AC02FFC7FF601D
- The copied delivery APK hash matches the final built APK.

The debug signing key is not included in the source ZIP. Consistent signing is required for ordinary data-preserving Android updates.

## What was not verified
No Android device or emulator was connected in the recorded device inventory. Therefore these are NOT established by the passing build:
- Visual quality on a real phone/tablet, large text and accessibility behavior.
- Live movie, series, anime or opt-in adult playback, subtitles and quality/audio changes.
- Actual scrolling frame times, startup/search latency, memory use or download throughput.
- Zero adult network traffic on an instrumented device, beyond the tested policy gates.
- Windows cold boot, real AVD launch, installation and repeat-start behavior.

The existing transfer engine is retained. Its implementation matches the inspected reference copy; no comparative download-speed benchmark was performed.

## Source limits
The 18 retained artifact digests were rechecked locally. Earlier manifest/classes.dex inspection is carried forward for identical binaries; the binaries were not executed by that audit.

AnimePahe and Porn36 are excluded. Adult defaults OFF. Normal source choices and the app-owned Celium Feed remain dependent on external providers.

Celium Feed uses up to three ready sources and a small collection preview from each. It is not a complete independent movie database or an exhaustive search service. Known title/year/type duplicates can collapse; uncertain versions stay separate.

The reviewed PornHub adapter has a fixed category list and direct query interpolation. App-side section truncation, ranking placement, deadlines and pagination were improved, but a rebuilt provider-specific search/category adapter is not included.

No MultiMovies binary patch or universal access guarantee is claimed. Its reviewed public repository master tree did not contain adapter source. No Streambert, NsfwBox or Stash code was imported.

## Remaining boundaries
Some inherited engine classes, resources and advanced dialogs remain because of shared references and extension compatibility. Normal consumer routes use the new UI, but this is not a claim of complete removal of every unused Cloudstream class.

Windows delivery runs Android in an existing emulator. It does not include the Android SDK/AVD, a native Windows .cs3 runtime or a standalone native application executable. No built-in VPN was added.

## Resume
Use CELIUM_STATUS.md and PLAN.md in the source archive. The updated implementation and research notes describe what changed and what still needs runtime verification. Do not restore the older adult-on default or AnimePahe startup loading.
