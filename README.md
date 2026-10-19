# Celium 5
A personal Android media app with a new neutral interface for browsing, search, details, saved titles, viewing history, downloads and settings.

This is an installable test build, not a claim of verified playback on every source or production readiness. Read [verification](VERIFICATION.md), [changes](docs/CELIUM_5_CHANGES.md) and [source research](docs/CELIUM_5_RESEARCH.md).

## Start
Install the APK from the delivery folder. The first launch prepares enabled sources internally and requires network access. No repository setup UI is needed. Adult content is disabled until explicitly enabled in Settings.

Home offers Celium Feed, assembled from a bounded source pool. Choose an individual source for its fuller catalog. Search uses one source at a time. New viewing history stays available after completion and can be removed without deleting saved titles or video files.

## Windows
Read [Windows starter instructions](windows/README.md). An existing Android SDK, emulator and AVD are required. Android .cs3 extensions are not native Windows plugins.

## Build
Read [CELIUM_BUILD.md](CELIUM_BUILD.md). The Android application ID for this debug build is app.celium.android.debug. Source, fonts and upstream license notices are included; SDKs, caches and build outputs are not part of the source archive.

## Compatibility
Celium preserves Cloudstream's Android extension/extractor and media engine. Some inherited runtime classes, advanced dialogs and resources remain. The ordinary consumer routes use the new Celium screens. Source reliability and network access still depend on each external provider.

[License](LICENSE) · [Current plan](PLAN.md)
