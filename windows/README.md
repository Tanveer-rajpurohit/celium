# Celium on Windows

Celium extensions are Android-only, so on a PC Celium runs inside an Android emulator. This package installs and opens the bundled `celium.apk` in your own Android Virtual Device (AVD). It is not a native Windows port.

## Prerequisites

- Android Studio with the Android SDK (Emulator and Platform-Tools installed).
- An AVD created in Android Studio's Device Manager.

These are not bundled.

## Setup

1. Edit `celium-windows.json`:
   - `sdkPath`: your Android SDK folder. Escape backslashes in JSON, e.g. `"C:\\Users\\you\\AppData\\Local\\Android\\Sdk"`.
   - `avdName`: the exact name of your AVD.
   - `emulatorPort`: an even port from 5554 to 5682 (default 5562).
2. Keep `celium.apk` in the package root, next to `Start-Celium.ps1`.

## Run

- Double-click `bin\Celium.cmd`. It starts the emulator if needed, waits for it to boot (up to 180 seconds), installs or updates Celium, and opens it. `Start Celium.cmd` in the root does the same.
- To check your config and files without starting anything, run from a terminal:

  ```
  bin\Celium.cmd -ValidateOnly
  ```

If something fails, the window stays open with the error message.

## Notes

- Updates use a data-preserving install. The launcher never uninstalls Celium or stops other emulators, and only targets the configured emulator port after confirming its AVD name.
- The execution policy bypass applies only to this script's own PowerShell process. No system settings are changed.

See the [Android emulator documentation](https://developer.android.com/studio/run/emulator-commandline) for help with AVDs.
