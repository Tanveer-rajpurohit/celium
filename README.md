# Celium 7
A personal Android media app for browsing, search, details, saved titles, viewing history, downloads and settings. Version 7.0.0-celium (71).

This is an installable test build, not a claim of verified playback on every source or production readiness. Read [verification](VERIFICATION.md), [changes](docs/CELIUM_CHANGES.md) and [source research](docs/CELIUM_RESEARCH.md).

## Start
Install the APK from the delivery folder. The first launch prepares enabled sources internally and requires network access. No repository setup UI is needed. Adult content is disabled until explicitly enabled in Settings.

Celium opens on Home, a combined Movies and TV Shows feed with a featured carousel. Movies and TV Shows are separate sections, each with its own scoped search. Choose an individual source for its fuller catalogue. The catalogue has 24 sources.

## Celium 7 changes
- Home: combined Movies + TV Shows feed with a featured carousel.
- Movies and TV Shows split into separate sections and searches.
- Home provider priority: MultiMovies, then CineStream, then Moviesmod, with backup when one is not ready.
- Feed cache shown instantly when under 6 hours old; every open refreshes in the background.
- Faster loading: providers load in parallel and shelves appear as they finish.
- Related videos show each title once.
- Layout size setting: Small, Medium (default), Large.
- Edge-to-edge status and navigation bars in the Celium theme colours.
- Sources: PikaShow and NetMirror Mobile removed; CNC Verse (Netflix · Prime · Disney+), MovieBox and CineStream added.
- New Celium app icon.

## Windows
Read [Windows starter instructions](windows/README.md). An existing Android SDK, emulator and AVD are required. Android .cs3 extensions are not native Windows plugins.

## Build
Read [CELIUM_BUILD.md](CELIUM_BUILD.md). The Android application ID for this debug build is app.celium.android.debug. Source, fonts and license notices are included; SDKs, caches and build outputs are not part of the source archive.

## Compatibility
Celium keeps the inherited Android extension/extractor and media engine. Some inherited runtime classes, advanced dialogs and resources remain. Source reliability and network access depend on each external provider.

Celium is a fork of Cloudstream — https://github.com/recloudstream/cloudstream, GPL-3.0

[License](LICENSE) · [Current plan](PLAN.md) · [Status](CELIUM_STATUS.md)
