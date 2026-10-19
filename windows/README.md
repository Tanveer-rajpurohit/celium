# Celium on Windows
This launcher runs the same Android APK in an existing Android emulator. It is not a native Windows port of Android extensions.

1. Install Android Studio and create an Android Virtual Device (AVD). These prerequisites are not bundled.
2. Edit celium-windows.json: set sdkPath (escape Windows backslashes as double backslashes in JSON) and your actual avdName.
3. Keep celium.apk beside the scripts. Double-click Start Celium.cmd.
4. To check paths without launching/installing, run Start Celium.cmd -ValidateOnly.

The launcher targets only the configured emulator serial, checks its AVD identity, waits up to 180 seconds and updates the APK with data-preserving install. It never uninstalls the application or stops unrelated emulators. A local marker avoids re-installing the same APK on every launch.

The wrapper permits this bundled script in its own PowerShell process; it does not change the stored machine/user execution policy, PATH, firewall, VPN or virtualization settings. Organization-enforced policies still apply.

[Official emulator documentation](https://developer.android.com/studio/run/emulator-commandline).

