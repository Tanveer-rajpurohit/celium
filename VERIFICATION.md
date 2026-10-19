# Celium 7 verification
Version 7.0.0-celium (71), application ID app.celium.android.debug.

## Celium 7 build results
Command (JDK 17, Windows):
```powershell
.\gradlew.bat :app:assembleStableDebug :app:testStableDebugUnitTest --no-configuration-cache --max-workers=2 --console=plain
```

| Check | Result |
| --- | --- |
| Android assembly / Kotlin compilation | BUILD SUCCESSFUL, exit 0, no source fixes needed |
| Unit tests | 30 run, 0 failures, 0 errors, 0 skipped |
| APK (aapt2 dump badging) | package app.celium.android.debug, versionName 7.0.0-celium, versionCode 71, label Celium |
| APK size | 92,570,396 bytes |
| APK SHA-256 | `ADDFC0581F1A5345BDB62DCD903360D6B4393E9225C4FCFA547BD4142A20EBA9` |

## Release outputs
- `celium-7.0.0.apk` (workspace root) and `windows\celium.apk`, both with the SHA-256 above.
- `Celium-7.0.0-PC.zip` (workspace root): one root folder `Celium/` containing `celium.apk`, `Start Celium.cmd`, `Start-Celium.ps1`, `celium-windows.json`, `bin/Celium.cmd`, `README.md`.
- Launcher check: `bin\Celium.cmd -ValidateOnly` was run from an extracted copy of the zip. It started and reported config validation ("Set sdkPath in celium-windows.json to your existing Android SDK folder."), which is the expected result when no SDK is configured. Exit 1, then `pause`.

## Sources
24 selected artifacts. The 21 carried forward from Celium 6 keep their earlier records. The 3 Celium 7 additions (CNC Verse v114 and MovieBoxProvider v52 from the CNC repo, CineStream v487 from the CSX repo) were checked against live plugins.json listings; their packages and hashes are unverified. PikashowProvider and CNC Verse Mobile are removed.

AnimePahe and Porn36 are excluded. Adult defaults OFF. All sources depend on external providers.

## What was not verified
Celium 7 has not been verified on a device or emulator. These are not established by any passing build:
- Home carousel, Movies/TV Shows split, layout sizes and edge-to-edge bars on a real screen.
- Live playback for any source, including the three new ones.
- Feed load time, cache behaviour and scrolling frame times.
- The PikaShow/NetMirror migration on an existing install.
- Windows emulator boot, install and bin launcher behaviour.

## Earlier baselines
Celium 6 (6.0.0-celium, 70) built successfully with 30 unit tests passing. Those results do not carry over to Celium 7.

## Remaining boundaries
Some inherited engine classes, resources and dialogs remain for extension compatibility. Windows delivery runs Android in an existing emulator; no native Windows .cs3 runtime, bundled SDK/AVD or built-in VPN. The debug signing key is not in the source ZIP.
