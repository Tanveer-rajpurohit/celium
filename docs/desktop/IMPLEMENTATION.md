# Windows desktop implementation status

Date: 2026-11-04. Local prototype 0.1.0. No commit, push, release tag, or published desktop release was made for this work.

## Implemented

- Replaced the inherited demo window with search, movie details, series episode selection, source selection, and an embedded VLC panel with pause, seek, volume, and fullscreen controls.
- Bundled an adaptation of Moviesmod version 33 from CSX commit `13903aa1c788a5cdd725b88d38bf2b9c1565a134`. Its README supplies GPLv3-or-later licensing; provenance and modifications are in [PROVIDERS.md](../../desktopApp/PROVIDERS.md).
- Moved domain discovery out of provider construction, initialized the provider once at application scope, encoded search terms, and retained details when optional metadata lookup fails.
- Deferred movie intermediary requests until source extraction. Limited series intermediary concurrency to three and preserved cancellation. Failed operations retain the current usable navigation context; partial source results are retained.
- Added an isolated, temporary Edge context for Moviesmod's JavaScript source navigation. It waits for a supported drive host, carries applicable cookies/user agent into subsequent requests, and closes its browser when the operation ends. Manual verification is user-driven. This is a narrow integration, not an implementation of the library's general JVM WebViewResolver.
- Added VLC 3 integration using vlcj. Referer/user-agent headers are passed as media options. Sources requiring other headers or separate audio tracks are explicitly unsupported rather than silently losing metadata.
- Added deterministic tests for domain validation, search-page recognition, browser-challenge detection, media headers, browser cookie scoping/expiry, and target-host recognition. Opt-in live tests are separate.
- Fixed the desktop icon path, set its independent prototype version, and built a local app image with a Java runtime. Edge and 64-bit VLC 3 remain external prerequisites.

## Actual verification evidence

| Check | Outcome |
| --- | --- |
| Existing desktop baseline | Compiled successfully after fetching missing desktop dependencies |
| Implemented desktop source | Compiled successfully |
| Final deterministic boundary tests | Seven passed, including cookie scope/expiry and destination-host recognition |
| Moviesmod initialization and repeated initialization | Worked in the live integration process |
| Inception search | One result returned |
| Inception details | Loaded as MovieLoadResponse after deferring intermediary requests |
| Breaking Bad search | Two results returned |
| Movie intermediary transport | Java HTTP/2 returned 429; an HTTP/1.1 probe reached the source page, so the desktop client uses HTTP/1.1 |
| Inception extraction | Not passed. HTTP-only extraction did not reach media; a real isolated Edge run timed out before reaching a supported video host. Subsequent popup handling was changed to inspect all temporary pages; the live movie flow was not rerun afterward |
| Breaking Bad episode loading | Not passed. `episodes.modpro.blog` returned HTTP 429 even after the HTTP/1.1 change and concurrency limit |
| Installed VLC decoder | Passed: W3C's public Sintel trailer produced video output and advanced media time beyond 1.5 seconds, using dummy video/audio outputs; test took about 16 seconds |
| Desktop process startup | Gradle run initialized Moviesmod. The task-created process was closed afterward to release files for rebuilding |
| Visual window/control inspection | Not performed: Computer Use app approval timed out |
| Windows local app image | `createDistributable` produced `desktopApp/build/compose/binaries/main/app/Celium/Celium.exe` and adjacent runtime/app files |
| Packaged executable smoke check | Started, remained running for five seconds, then the task-created process was closed. Generated application directory is about 335 MiB. This is startup evidence, not visual UI verification |
| Actual embedded Moviesmod playback | Not verified; live source extraction remains the preceding blocker |

The opt-in integration run containing the series check correctly failed (eight tests: six boundary tests and one native decoder test passed; one series test failed). Earlier movie integration checks also failed and are not counted as successful playback. The final ordinary build passed with seven deterministic tests and three live checks skipped, and rebuilt the Windows app image. That green ordinary run does not supersede the recorded live-provider failures.

Build logs and JUnit reports are generated under ignored `.gradle/` and `desktopApp/build/` paths. Gradle reports an inherited Coil/Skiko dependency-version warning; visible rendering still needs verification. Android source, common library behavior, signing identity, version, and release workflow were not changed by this desktop implementation; no Android regression build was run.

## Next gate

Resolve the real Moviesmod intermediary/verification path and series host response, then demonstrate a movie and an available episode through extraction and visible embedded playback, including seek, source failure recovery, and cancellation. Treat provider-host changes separately from player defects. A general plugin loader, downloads, subtitles/audio selection, automatic updates, installer release, and other operating systems remain outside this prototype.

Before publishing desktop, protect the Android latest-release updater (including old installed APKs), add separate desktop tags/workflow, and verify packaging and prerequisites. Keep the complete generated application directory together; the EXE alone is not portable.
