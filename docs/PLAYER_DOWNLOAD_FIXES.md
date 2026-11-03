# Player and download fixes

Author: wakeupguru
Date: 2026-11-03

## Reported issues

1. Playback waits on “Skip Loading” while a counter keeps increasing. Start playback automatically when a usable source arrives.
2. Remove on-screen “Trying provider” messages and decorative emojis from provider/source labels.
3. Make “Quality and source” show video sources and their quality, rather than mixing in subtitles.
4. Use the spelling “Subtitles” throughout the affected controls.
5. Clean source names, including confusing bracketed extractor labels such as VOE, MP4, MixDrop and Byse. Keep enough information to distinguish sources.
6. Put extra player controls, including subtitle sync, under a consistent menu button.
7. Make audio/subtitle controls show actual audio and subtitle tracks. Keep video resolution with video quality.
8. Show common resolution labels such as 720p, 1080p and 1440p instead of pixel dimensions such as 1994x1080.
9. Remove the quality profile selector from the normal source picker. It controls source ranking and belongs in settings.
10. Fix online subtitle selection: selecting a search result and applying it should load subtitles or report a useful error. Inspect archive/download result handling and the unexplained result icon.
11. Keep player controls consistent across movies, episodes and providers; unavailable tracks should have clear empty states.
12. Download choices should show source, quality and file size when known. Never invent missing quality or size.
13. Failed downloads should remain visible with a “Download failed on this source” card and a way to choose another source for the same movie or episode.
14. Preserve title/episode context when a download fails so users can retry another source without searching again. Do not unexpectedly interrupt active playback or navigation.
15. TODO, later: download each TV season as a ZIP file.

## Working hypothesis

Use the existing player, subtitle and download flows. Separate video source selection from audio/subtitle selection, simplify display labels without changing provider identities or URLs, and surface download failures with a route back to the original item.

Unknown quality and size must be labelled honestly. Source extraction can return broken links; starting playback early must retain fallback sources. Subtitle archives can contain several files and cannot be treated as subtitle text. Existing download files and saved progress must survive these changes.

## Scope and validation

Inspect player generation/loading, source and track dialogs, subtitle search/application, download selection, queue persistence, and Celium download cards. Update shared display helpers and affected resources/callers; add focused tests for new logic and run available build checks.

Season ZIP downloads are deferred. Live provider behavior needs device/network verification after local checks.

## Changes implemented

- Start playback as eligible links arrive; keep discovering fallback links. If the first link fails, wait for later links before showing the final failure screen.
- Remove the “Trying” toast. Strip source decoration and square brackets, preserve meaningful host/language names, and number choices so repeated names remain distinguishable.
- Separate source/video quality from audio/subtitle selection. Keep single audio tracks visible, expose subtitle selection even without embedded tracks, and show common resolution labels.
- Remove the profile control from the source picker. Keep extra player controls under the three-line More menu, with an explanation when subtitle sync is unavailable.
- Reset online subtitle selection on a new search, cancel obsolete searches/downloads, explain sound-description results, reject empty resources, preserve subtitle file types, and let users choose a file from multi-file archives.
- Show source, quality and available direct-video file sizes in download choices. Missing metadata stays explicitly unknown.
- Keep the player open when queuing a download. Retain failed cards, show a failure notice in the player, and offer another source for the same title/episode.
- Separate download failure from cancellation. On a confirmed replacement, wait for the old writer, verify partial-file cleanup, update the stored source, and then requeue. Completed files and playback progress remain untouched.

The English resources already spell “Subtitles” correctly; no misspelled English resource was found. Host names such as VOE and MixDrop identify different video hosts, so their plain names remain to distinguish sources.

## Deferred TODO

- [ ] Download each season of a TV series as a ZIP file.

## Validation

Focused tests cover source labels, unknown quality, subtitle MIME types, late fallback links, failure/cancellation state, archive file filtering and archive expansion limits. The Android unit-test suite and debug APK build are the build checks. Live subtitle services, phone/TV controls and provider downloads still require device testing.

Final checks passed on 2026-11-03: `:app:testStableDebugUnitTest :app:assembleStableDebug`, with 47 tests, zero failures/errors/skips. Player XML parsing and `git diff --check` passed. Debug APK: `app/build/outputs/apk/stable/debug/app-stable-debug.apk`.
