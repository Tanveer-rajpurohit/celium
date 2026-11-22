# Celium repository structure

Android and desktop live in this repository. Native Windows desktop is the current development focus; website development is deferred. Each app owns its platform code; reusable Kotlin code stays in the existing shared modules. Keep the current Gradle module paths so Android builds and releases continue to work.

```text
celium/
|-- app/                         Android application
|   `-- src/                     Android screens, player, downloads, resources, tests
|-- desktopApp/                  Native desktop application (existing Compose/JVM module)
|   `-- src/
|       |-- jvmMain/kotlin/       Desktop entry point and platform-specific Kotlin code
|       `-- desktop-icons/       Desktop installer icons
|-- shared/                      Shared Kotlin UI, resources, and application helpers
|   `-- src/
|       |-- commonMain/          Code supported by all configured targets
|       |-- androidMain/         Android implementations of shared functionality
|       |-- jvmMain/             JVM implementations used by desktop
|       `-- jvmTest/             Shared JVM tests
|-- library/                     Provider/extension API and reusable core code
|   `-- src/                     Common and platform-specific code and tests
|-- docs/                        Design notes, requirements, release notes, update guide
|-- scripts/                     Release packaging and repository maintenance scripts
|   `-- tests/                   Tests for those scripts
|-- .github/workflows/           CI checks and release automation
|-- windows/                     Existing Android-emulator launcher; not the desktop app
|-- fastlane/                    Android store metadata and translations
|-- gradle/                      Gradle wrapper and dependency/version configuration
|-- build/                       Generated build and release output (ignored by Git)
|-- .signing/                    Local private signing material (ignored by Git)
|-- settings.gradle.kts          Registers existing Gradle modules
|-- build.gradle.kts             Root Gradle build configuration
`-- structure.md                This directory guide and release boundaries
```

## Where new code goes

- Android-only changes belong in `app/`.
- Desktop windows, navigation, playback integration, downloads, and OS integration belong in `desktopApp/src/jvmMain/kotlin/`. Follow the existing package `com.lagradost.cloudstream4`; create feature packages when implementing those features.
- Reuse `shared/` and `library/` when code actually works across their configured targets. Do not put Android classes into common code. Do not assume Android extensions work on desktop or in a browser.
- Put tests alongside the module they verify. Keep cross-platform design decisions and release instructions in `docs/`.
- A future native iPhone/iPad app can get an `iosApp/` directory when implementation starts. It is not configured now.

## Current platform status

- **Android:** existing app and APK release tooling.
- **Desktop:** Windows prototype with Moviesmod source adaptation, search/details/episodes/source selection, and VLC playback integration. Live extraction and visible playback remain subject to the [implementation status](docs/desktop/IMPLEMENTATION.md). Other operating-system installer formats remain unverified.
- **Website:** deferred; no website application, hosting, or deployment is configured. Existing web source directories in the library are inherited and are not a website app.
- **Apple native app:** not configured.

## Releases: keep each platform separate

Code pushes and releases are separate operations. A desktop-only change does not require a new Android version. Changes to `shared/` or `library/` need checks for every affected app.

| Platform | Version ownership | Release destination |
| --- | --- | --- |
| Android | `app/build.gradle.kts` | Signed APK, Android update manifest, checksums |
| Desktop | `desktopApp/build.gradle.kts` | Windows installer first; other operating systems later |

The current Android release workflow, `.github/workflows/celium-release.yml`, uses `vX.Y.Z` tags. Keep that convention working until release automation and its tests are deliberately migrated together.

Before publishing the first desktop release:

1. Make Android's updater select Android releases instead of relying on GitHub's overall latest release. Existing installed APKs using the old updater must also be accounted for.
2. Give desktop its own workflow and `desktop-vX.Y.Z` tags. Do not send desktop tags through the Android release workflow or mark desktop releases as the overall latest while older Android clients depend on it.
3. If switching Android to `android-vX.Y.Z`, update tag parsing, packaging, updater behavior, tests, and release instructions together. This is planned, not implemented by this scaffold.

Never commit signing keys, passwords, local SDK configuration, caches, or generated installers. See [the Android update guide](docs/CELIUM_UPDATES.md) for the current signed APK process.
