# Earlier Cloudstream personal build notes

Historical notes for the prior Cloudstream fork. For Celium's current behavior and build, use README.md, CELIUM_BUILD.md and CELIUM_STATUS.md instead.

This version is based on recloudstream/cloudstream. The upstream GPL-3.0 license and attribution remain in this source tree.

Base revision: `4c4c6796ff6a972e8f1339675b349e8c53194577`.

## What changes

- Dark cinema styling, larger hero titles, horizontal poster shelves, and larger touch controls.
- Search runs after 450 ms of idle typing. Enter records a query in history; typing does not fill the history with fragments.
- Six providers can search simultaneously. Results appear as providers complete, with result/source/failure counts. Exact titles rank above prefixes, phrases, and partial word matches. Alternative sources remain selectable.
- A default Discover feed combines up to eight installed providers, prioritizing pinned providers and respecting media/language preferences. Three sources can load concurrently, and shelves appear as each finishes.
- Homepage sections have isolated failures, bounded concurrency, a two-minute memory cache, and up to 30 minutes of stale fallback after a network failure. This cache is not persisted across app restarts.
- Pagination removes repeated source URLs, keeps failures retryable, and ignores results from a previously selected feed or query.
- Player controls have clearer focus states, accessible labels, and a larger timeline touch area. The existing Media3 engine, source headers, subtitles, track selection, resume behavior, and mirror fallback are retained.

## First launch

The personal build seeds these repositories once:

1. [Phisher Repo](https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json)
2. [CXXX](https://raw.githubusercontent.com/phisher98/CXXX/builds/CXXX.json)
3. [GizliKeyif](https://raw.githubusercontent.com/Kraptor123/Cs-GizliKeyif/refs/heads/master/repo.json)

New installations enable adult providers and automatic extension downloads. A large repository can take time to install on first launch. Existing explicit preferences are retained. Repositories removed later are not silently re-added. Automatic updates and downloads remain configurable in Settings.

Automatic checks for upstream app updates default to off in this personal version. Extension updates remain enabled. Use builds from this modified source to retain its behavior; an official app update does not include these changes.

If migrating data with a previously selected home source, select Discover in the home source picker to use the combined feed. Pin preferred sources to prioritize them. The home source picker still exposes individual providers.

## Network settings

Settings → General → App proxy accepts `http://host:port` or `socks://host:port`. Credentials and URL paths are unsupported. Empty uses the device network, system proxy, and VPN routing. Restart after changing the setting to reconnect existing clients and playback.

Configured proxies apply to the shared HTTP client, artwork, and Media3 playback. Playback uses OkHttp instead of Cronet when an explicit proxy is configured, preserving extractor headers and referers. Extensions using independent clients, WebViews, torrents, or external players can behave differently. Use a device-level VPN when all device traffic needs the same route. Availability still depends on each source, its network restrictions, and extension maintenance.

## Build

Use JDK 17 and the Android SDK required by the version catalog (currently API 37). With `JAVA_HOME` and `ANDROID_HOME` set:

If building from the source ZIP, run `git init` once in the extracted project folder first. The upstream build reads Git metadata; an empty local repository is sufficient and no commit is required.

```powershell
.\gradlew.bat :app:assembleStableDebug :app:testStableDebugUnitTest :app:lintStableDebug --no-configuration-cache --max-workers=4
```

The debug APK is produced under `app/build/outputs/apk/stable/debug/`. It uses the upstream debug application ID (`com.lagradost.cloudstream3.debug`). Do not install it over an unrelated debug build without backing up its data. The normal release app uses a different ID.

The added unit tests cover title ordering, Unicode handling, source-preserving deduplication, and proxy validation. Compilation does not establish that every third-party site or extension works; that requires device and source testing.

## Compatibility and scope

`MainAPI`, extension result types, the Android class loader, and plugin registration contracts are unchanged. The original two-argument `APIRepository.getMainPage` entry point is retained. Discovery adapts existing provider responses rather than replacing the extension runtime.

Native Windows work is deferred. Legacy Android extensions require Android's class loader; this source does not claim to run those binaries directly on Windows.

The bounded source counts trade exhaustive simultaneous fetching for predictable resource use. Pinning and individual source selection provide access to providers outside the initial Discover feed.
