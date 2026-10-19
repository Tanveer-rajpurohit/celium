# Celium 5: source architecture findings
Research date: 18 October 2026. This is implementation context, not a promise of source availability.

## What the requested projects actually provide
| Project | Observed approach | Decision for Celium |
| --- | --- | --- |
| [Streambert](https://github.com/truelockmc/streambert#streaming) | Electron desktop app; TMDB supplies much of the discovery/search metadata, while external providers supply streams. Its anime path uses different metadata and media services. | Adopt the separation of discovery and playback, not an assumption that Electron eliminates provider failures. No Streambert code or external stream endpoints were copied into Celium. |
| [NsfwBox](https://github.com/Kisspeace/NsfwBox) | Delphi / FireMonkey Android and Windows app using multiple scraper/API integrations. | A reference for categorized source integrations and download management, not a drop-in Kotlin runtime or universal content API. No code or source integrations were imported. |
| [Stash](https://github.com/stashapp/stash#stash) | Go-based self-hosted organizer/player for an existing media collection, with metadata scrapers and plugins. | Useful future option for owned/authorized personal media and reliable local browsing. Its metadata services do not supply a universal streaming catalog. |
| [Cloudstream](https://github.com/recloudstream/cloudstream) | Existing Android extension, extractor and playback runtime used by this project. | Preserve compatibility while replacing consumer presentation and adding an app-owned feed coordinator. |

Open-source software licenses do not establish permission to access or redistribute the media listed by a source. Prefer owned/licensed libraries and permitted APIs where available. Celium has not added DRM, access-control or CAPTCHA bypass.

## MultiMovies
The [current site](https://m1.multimovies.courses/) exposes useful movie/series, language, network, anime and collection navigation. The selected binary adapter remains the compatibility path.

The [public master tree of the selected Phisher repository](https://api.github.com/repos/phisher98/cloudstream-extensions-phisher/git/trees/master?recursive=1) returned seven documentation/metadata entries during inspection, without MultiMovies adapter source. Therefore this update does not claim to patch or rebuild that adapter, or verify every current website feature. A sampled detail fetch in earlier research also failed. A richer app-owned site adapter still requires permitted endpoints, fixtures and runtime verification.

Celium Feed combines a bounded selection of existing adapters. It keeps original URLs, API identity and request headers. Same-title/year/type entries can collapse across sources; unknown-year versions remain distinct to avoid merging remakes or unrelated items. Each shelf retains its source for pagination. Individual sources remain available for deeper browsing beyond the curated preview.

## Adult categories and search
The [reviewed PornHub adapter source](https://raw.githubusercontent.com/Kraptor123/Cs-GizliKeyif/master/PornHub/src/main/kotlin/com/kraptor/PornHub.kt) contains a fixed main-page list with many commented-out categories. Its search implementation directly interpolates query text into a URL. These are provider-side limitations worth fixing in a separately tested adapter revision; this app update does not claim that provider bug is repaired.

There were also app-side limits: only the first 16 feed sections were requested, search ranking ran on the UI thread, and provider pagination could keep advertising more pages even when results repeated. Celium now exposes additional source sections in batches, moves ranking off the UI thread, bounds search time, and stops empty/repeated continuations.

A VPN is a network transport choice, not a fix for a parser, a category list or a malformed query. This update does not include a VPN service or guarantee access to blocked sites. Use the device's configured VPN when appropriate; source-side restrictions and failures remain observable.

## Windows
The supplied starter runs the new Android APK in an existing Android emulator. It checks the selected AVD identity and targets one serial. It is not a native Windows .cs3 runtime. An Android SDK/emulator/AVD is not bundled.

