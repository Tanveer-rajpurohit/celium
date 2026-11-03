# Player and download fixes — concepts brief

Date: 2026-11-03  
Author: wakeupguru  
Input: [reported issues](docs/PLAYER_DOWNLOAD_FIXES.md)  
Mode: BRAINSTORM

## Overview

Repair the current player, subtitle-resource and download flows. Start playback on the first eligible source, separate video choices from audio/subtitle tracks, clean display labels, and make failed downloads recoverable using their original movie/episode context. Preserve provider identities, source URLs, selected tracks, downloaded files and saved progress.

This is a synthesis of six completed fresh-conversation-context reviewers. They inspected the concept and their assigned bundled personas; no document standards were supplied. The working tree was changing during inspection, so line anchors below describe inspection snapshots, not a frozen revision. The final structural reviewer saw the in-progress `StreamLabels` helper. Review findings identify requirements and risks, rather than certifying the current implementation.

## Reviewer status

| Reviewer | Lens | Status |
| --- | --- | --- |
| egm-design | Architecture and failure modes | Completed |
| egm-codebase-details | Existing implementation and reuse | Completed |
| egm-zoom-out | Simplicity and code minimization | Completed |
| egm-comprehension | UX and ergonomics | Completed |
| egm-implementation-readiness | Compatibility and safe replacement | Completed |
| egm-structural | Coupling and maintainability | Completed |

Every reviewer returned an actual report identifying files read. All were read-only; no reviewer ran builds or device/provider checks. Fresh conversation context does not establish exclusion of host instruction files.

## Scope and user contracts

### Playback startup and fallback

- Start once an eligible source exists, using the existing profile exclusions and source ordering. An extracted URL is only a candidate; playback may still fail.
- Keep source extraction running after startup. Retain late links and subtitles, refresh existing fallback order, and never restart working playback because a better source arrives.
- Preserve one startup guard, episode/instance checks, saved position and manual source selection. Stale callbacks must not affect another episode.
- If the first source fails before another arrives, remain eligible to use later sources while extraction runs. Show terminal failure only after discovery and eligible fallback attempts are exhausted. Each failed source is attempted at most once per fallback attempt.
- Remove the transient “Trying provider” presentation while retaining useful diagnostics. Check initial loading versus first-link ordering so a loading observer cannot release newly started playback.

Existing owners: `PlayerGeneratorViewModel.currentLinks` and `loadLinks`, `GeneratorPlayer.startPlayer`, `playerSourceList`, `nextMirror`, and `LinkFallback.withOrder`. Inspection anchors: `GeneratorPlayer.kt` around 1847, 1932–1960 and 2615–2669; `PlayerGeneratorViewModel.kt` around 193–236 and 384–440.

### Source, quality and track controls

- “Quality and source” contains video sources and real video-quality choices. Audio and subtitles use actual player/subtitle inventories, retaining track identity rather than deriving tracks from extractor names.
- Show real single tracks as well as multiple tracks. Distinguish track discovery from an empty inventory; keep an explicit subtitles Off option. Preserve playback position and the selected language/track where available when changing source.
- Use “Subtitles” consistently. Put extra controls, including subtitle sync, under the same accessible menu button across movies, episodes, providers and supported player layouts.
- Remove quality-profile editing from the ordinary picker while preserving ranking/profile behavior and its settings entry point.
- Clean labels exclusively for display. Preserve original names, provider/source IDs, URLs, headers, referers and deduplication keys. Retain useful extractor/source information and distinguish labels that become equal after cleanup.
- Format known reported height as `720p`, `1080p` or `1440p`, including nonstandard widths. Unknown quality remains unknown: `Qualities.Unknown.value` is 400 and must never become `400p`. Preserve genuine adaptive Auto semantics where applicable.

Reuse `StreamLabels` where present in the working tree, `showMirrorsDialogue`, `showTracksDialogue`, existing subtitle grouping and the player track APIs. Reach `FullScreenPlayer`, phone/TV custom controls, portrait/landscape source layouts and affected resources. Inspection anchors: `GeneratorPlayer.kt` around 596, 1208, 1501 and 1616–1768; `FullScreenPlayer.kt` around 1505; `IPlayer.kt` track interfaces.

### Online subtitles

- Resolve resources through `SubtitleRepo` and `SubtitleResource`; retain provider distinctions between a raw subtitle URL and an archive download.
- Repair filtering, format/name preservation and errors at the shared archive/resource boundary. Ignore directories and unsupported files; reject empty/corrupt results and bound downloaded/expanded data and archive entry count. Preserve headers, language and original format rather than guessing MIME from a `.tmp` URI.
- One supported file applies directly. Several supported files offer filename/language selection through the existing subtitle selection surface. Archive entry order must not silently decide the subtitle.
- Show loading and useful errors for missing providers, resolution, download, archive and player-loading failures. A selection request alone is insufficient evidence that decoding/display succeeded. Keep the previous subtitle and playback usable when resolution/application fails.
- The existing unexplained icon denotes hearing impairment; explain it with a visible/accessibility label or remove unexplained presentation. Do not interpret it as a success indicator.
- Reach both manual and automatic subtitle-resource callers so their format/error handling agrees.

Existing owners: `subtitles/AbstractSubProvider.kt` (`SubtitleResource`), `syncproviders/SubtitleRepo.kt`, `SubtitleAPI.kt`, and player subtitle application/grouping. Inspection anchors: `AbstractSubProvider.kt` around 19–80; `GeneratorPlayer.kt` around 803–824, 999–1101 and 1194. Providers such as SubSource/Subdl explicitly use ZIP resources; do not assume every provider returns an archive.

### Downloads and failure recovery

- Both player and result-page source pickers show a distinguishable source, quality and known size. Use explicit unknown states when absent; never infer HLS totals or treat downloaded bytes as file size.
- Reuse available size metadata or the existing cached/time-limited direct-video size helper where appropriate. Show choices promptly; avoid mandatory per-source metadata request fan-out.
- Keep failed cards visible with “Download failed on this source” and an explicit “Choose another source” action for the exact movie/episode. Failure events update state passively, preserving playback and navigation.
- Reuse the Celium record store, shared action model and result/download source-selection flow. Keep same-source Retry distinct from source replacement. A chooser opened for recovery must not invoke player source switching, pause playback or automatically navigate to Downloads.
- Resolve recovery context from the full queue item; for resume-only records use available cached header context. If the provider or required original context is unavailable, retain the failed card and report that limit without guessing URLs or routing to a different episode.
- Opening/canceling a chooser leaves files, resume state and the failed record untouched. On explicit replacement, stop the old writer, complete required cleanup successfully, replace the recorded wrapper for the same item, and only then queue the new source. Never append incompatible source bytes to an existing partial file.
- Preserve download row identity/order and completed files. Ignore stale chooser results after cancellation/navigation and prevent duplicate replacement taps from racing.

Existing owners: `GeneratorPlayer.onPlayerDownloadClick`, `ResultViewModel2` mirror selection, `DownloadQueueItem`/`DownloadQueueWrapper`, `CeliumDownloads`, `DownloadBook.actions`, `CeliumDownloadControl` and `CeliumDownloadsFragment.ProgressRow`. Inspection anchors: `GeneratorPlayer.kt` around 607–674; `ResultViewModel2.kt` around 1220–1240 and 1488–1490; `DownloadObjects.kt` around 27–184; `CeliumDownloadState.kt` around 156–195, 251–325 and 337–375.

## Analysis: prioritized implementation risks

| Severity | Risk | Required resolution |
| --- | --- | --- |
| Major | First source fails before remaining extraction yields alternatives | Keep the existing attempt eligible for late fallback and distinguish discovery-running from terminal exhaustion. |
| Major | Loading and source observations arrive independently | Preserve instance checks and a single startup guard; verify first-link/loading/completion ordering. |
| Major | Retry currently deletes partial data and requeues the original wrapper | Add deliberate replacement behavior in the existing action flow; preserve bytes until user commits and cleanup succeeds. |
| Major | `record()` preserves an existing wrapper | Confirmed replacement must update persisted source/context, or subsequent retries use stale data. |
| Major | Stop/deletion work is asynchronous and existing paths use fixed delays | Wait for actual completion, honor deletion failure, and prevent old cleanup from affecting the new writer sharing an episode ID. |
| Major | Failure cleanup emits `IsFailed` and a Stop action; Celium removes records on `IsStopped` | Trace propagation and preserve failure intent separately from explicit user cancellation. Verify failed-card retention through restart. |
| Major | Resume-only records lack original result URL | Recover from existing header metadata where available; tolerate absent context with a useful unavailable-action state. |
| Major | Archives currently lose format information and can yield unsupported entries | Fix the shared resource boundary with validation, bounded extraction, format preservation and multi-file choice. |
| Nit | Display cleanup can collide or change fallback keys | Use presentation-only cleanup and deterministic distinguishing text; retain raw identities. |
| Nit | Size fetching can multiply provider requests | Prefer known values and honest unknowns; preserve headers/timeouts for optional existing probes. |

These are requirements to address during implementation, not additional products or infrastructure. Use existing diagnostics with bounded stage/reason information where needed. Do not add full signed URLs, credentials or headers to diagnostics; existing full-link logging merits care when touching these paths. A new persistent failure-reason schema is optional, not required for this scope.

## Compatibility and rollout

Keep current persistence keys, IDs, serialized field names, existing wrappers and offline file layout. Prefer existing context; any essential new retry-context fields must be optional/defaulted and readable when absent. Verify both serialization paths. No destructive migration or bulk requeue is needed.

Rollback must leave completed downloads, partial files, resume metadata and older records readable. Do not clear records or rewrite provider names to implement presentation cleanup. Existing serializers reportedly ignore unknown fields; verify relevant types before relying on additive-field rollback compatibility.

## Focused verification

1. Early eligible source, stale callbacks, loading/completion ordering, manual choice, late better source, early source failure followed by a late alternative, and exhausted extraction.
2. Unknown-quality sentinel, reported height, cleaned-label collisions, unchanged identity keys, real single/empty tracks, selected-track retention and subtitle Off.
3. Raw subtitle resources and ZIPs with one/multiple supported files, directories, unrelated files, corruption/empty archives and excessive expansion; preserve MIME, language, headers and previous subtitle on failure.
4. Legacy full/resume-only records, available/missing cached retry context, unavailable providers and additive-field serialization if used.
5. Failure → Stop propagation → restart retains one failed card for the exact item. Choosing/canceling another source preserves files/progress; confirmed replacement updates its persisted wrapper.
6. Slow stop/deletion, cleanup failure, repeated replacement taps and navigation/cancellation cannot erase or requeue a replacement. Completed downloads stay playable and excluded from retry.
7. Both download entry points show honest metadata; recovery does not interrupt playback or navigate automatically. Check phone/TV and affected dialog orientations.

Add small focused tests for new state/format/archive logic and run available project build checks. The inspected subtitle unit test covers language tags rather than these archive/state scenarios. Live provider extraction, subtitle decoding, playback, accessibility and real storage cancellation still require device/network verification.

## Explicit deferrals

Season ZIP downloads remain later work. Skip a new player/downloader architecture, second failure ledger, parallel track inventory, provider-specific archive parsing in dialogs, mandatory source-size crawler, new tracing platform and speculative settings.

## Goals vs. Non-Goals

In scope: the playback, source labels, quality/track controls, subtitle application and download recovery fixes listed in the issue inventory. Reuse existing player and download state.

Out of scope: season ZIP downloads, provider service guarantees, guessed file sizes or guessed quality, and a replacement player/downloader.

## Alternatives Weighed

- A new player or download framework was rejected because existing flows already provide track selection, fallback and saved queue context.
- Automatically navigating away on a background download failure was rejected because it would interrupt playback or unrelated navigation. Keep a failure card and offer explicit recovery.
- Inferring download size from a streaming playlist was rejected because playlist length is not the finished file size. Query direct video sizes using the existing helper; show unknown otherwise.
- Removing all host/language information was rejected because it makes different sources indistinguishable. Clean decoration and brackets while retaining meaningful names and numbering choices.

## Key Decisions & Staging for Next Steps

Apply shared display helpers and preserve actual source identities. Start playback early while continuing discovery, separate the source picker from audio/subtitle controls, preserve subtitle extensions, and distinguish download failure from user cancellation. Replacement downloads must wait for the previous writer and successful cleanup before starting. Run focused tests and the Android build; record device/provider verification separately.
