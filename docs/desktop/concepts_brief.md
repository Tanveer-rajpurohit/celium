# Moviesmod on Celium desktop

* **Author:** wakeupguru
* **Date:** 2026-11-03
* **Status:** Six-lens concept review complete; feasibility gates remain open

## Overview

Build a native Windows Celium client from the existing Compose/JVM `desktopApp` module. Moviesmod is the first provider. Provider requests, extraction, browser verification if needed, and embedded playback run on the user's machine. Prove search → details → episode selection when applicable → extraction → source selection → actual playback before expanding the provider list. Preserve Android builds and updates; defer the website.

**Readiness decision:** proceed to a bounded Moviesmod compatibility and playback spike. A full interface build or claim of Moviesmod support is premature. This is a reviewed concept, not a completed implementation design or runtime certification.

## Analysis

### Local evidence

| Evidence read | Consequence |
| --- | --- |
| [Desktop entry point](../../desktopApp/src/jvmMain/kotlin/com/lagradost/cloudstream4/Main.kt), [build](../../desktopApp/build.gradle.kts), and [README](../../desktopApp/README.md) | Existing demo shell, no provider journey or player implementation. Declared installer formats are not verified installers. |
| [Selected sources](../../SELECTED_SOURCES.json) | Moviesmod version 33, author megix, selected Android `.cs3` artifact/hash and CSX repository; no proven JVM artifact or adaptation source revision. |
| [BasePlugin](../../library/src/commonMain/kotlin/com/lagradost/cloudstream3/plugins/BasePlugin.kt) | Reusable provider/extractor registration and `load()`, but registration appends to global collections without transactional rollback or deduplication. |
| [Compatibility investigation](../DESKTOP_PROVIDER_COMPATIBILITY.md), Android [CloudflareKiller](../../app/src/main/java/com/lagradost/cloudstream3/network/CloudflareKiller.kt), [JVM resolver](../../library/src/jvmMain/kotlin/com/lagradost/cloudstream3/network/WebViewResolver.jvm.kt) | Android loader/context/resources cannot be reused unchanged. CloudflareKiller imports Android CookieManager/logging, clears WebView cookies during construction and calls `getWebViewUserAgent()`, absent from the inspected JVM resolver. JVM interception passes through; explicit browser resolution throws TODO. This is a compile/API and browser-capability blocker, not just an archive-extension issue. |
| Existing [MainAPI](../../library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt) and [ExtractorLink](../../library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt) contracts | Reuse search/load/loadLinks, movie/series/episode models, media metadata, headers/referer and `getAllHeaders()`. Signature alignment alone proves little about complete provider compatibility. |
| [Shared](../../shared/build.gradle.kts) and [library](../../library/build.gradle.kts) target Android and JVM | Desktop fixes in common code can regress Android. Keep browser/player platform dependencies in desktop/JVM code. |
| [Release workflow](../../.github/workflows/celium-release.yml), [Android updater](../../app/src/main/java/com/lagradost/cloudstream3/ui/settings/GithubReleases.kt), [release packager](../../scripts/prepare_release.py), [structure guide](../../structure.md) | Android uses `vX.Y.Z`, signed APK/manifest packaging, and GitHub overall latest release discovery. Desktop-only latest publication can break old Android clients. New updater code alone cannot protect installed old APKs. |

All reviewers read the target and the ten selected repository files: compatibility investigation; desktop README/build/entry point; shared/library builds; BasePlugin; JVM resolver; release workflow; SELECTED_SOURCES.json. Individual reports distinguish additional full reads from targeted searches. The synthesizer also read structure.md, Android CloudflareKiller, GithubReleases, CeliumUpdateManifest and prepare_release.py.

### Upstream evidence and unknowns

Prior investigation recorded the [Moviesmod index](https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/plugins.json) advertising `.cs3` version 33 without a corresponding JAR in that inspected index; this does not prove none exists elsewhere. It reported [provider source](https://github.com/SaurabhKaperwan/CSX/blob/master/Moviesmod/src/main/kotlin/com/megix/MoviesmodProvider.kt) using CloudflareKiller for home/search, remote domain discovery and blocking network initialization; [extractor source](https://github.com/SaurabhKaperwan/CSX/blob/master/Moviesmod/src/main/kotlin/com/megix/Utils.kt) with redirects/cookies/headers; and [module](https://github.com/SaurabhKaperwan/CSX/blob/master/Moviesmod/build.gradle.kts)/[root](https://github.com/SaurabhKaperwan/CSX/blob/master/build.gradle.kts) builds with Android configuration and no explicit Moviesmod cross-platform opt-in.

Those are prior inspection observations on mutable branches, not independently revalidated or executed by this swarm. Upstream `Moviesmod.kt` plugin-entry and LICENSE fetches failed; their contents, redistribution permissions, exact source commit and complete dependency compatibility remain unverified. No maintained compatible JAR, browser/player choice, codec coverage or Windows resource baseline is established. Moviesmod is not presumed HTTP-only; a challenge may be required even for search.

### Prioritized synthesis

* **Blocker for provider implementation:** verify a pinned, licensed Moviesmod source/JAR route, entry point and dependency closure; compile it against this library. Android challenge handling cannot be copied unchanged. This blocks implementation assumptions, not further concept work.
* **Major for end-to-end support:** JVM fallback may pass an HTML page through rather than throw. A nonempty URL, populated source list or registration success does not prove playable media. Unsupported browser behavior must be explicit; verify actual challenge and embedded playback paths.
* **Major for the usable prototype:** initialize once outside Compose recomposition and the UI thread; bound network stages, preserve cancellation and suppress obsolete results. Failure/retry must not duplicate or partially register providers. Handle this at the smallest desktop boundary, without a broad shared-helper refactor.
* **Major before publication:** separate desktop releases and protect old Android latest-release discovery. This need not delay local desktop development.
* **Staged maintenance:** pin source, record adaptation changes/ownership and a reproducible working baseline; measure runtime costs. Automatic plugin updates, broad logging infrastructure and additional installers are deferred.

Local execution removes hosted provider compute/database/media-egress operations but uses each user's memory, CPU, disk, bandwidth and upstream request allowance. Start with one playback session, on-demand extraction and bounded requests. Measure cold start, installed size and idle/peak memory for the chosen runtime; do not invent quotas or resource guarantees.

## Goals vs. Non-Goals

**Goals:** a Windows build; Moviesmod registration and compatibility by stage; local search/details/available episodes/source selection; embedded playback preserving required request metadata; Android compatibility checks for shared changes.

The minimum usable flow needs initializing/ready/failed states, visible progress, bounded waits and cancellation. Distinguish empty results from failed searches, no sources from failed extraction, and player startup failure from provider failure. Retry the failed stage and preserve query/title/episode/source context. Label sources using available host/quality/language metadata. Provide play/pause, seek where supported, volume/fullscreen and return to source selection, with keyboard navigation, visible focus, accessible names and window resizing. The demo currently disables its default focus outline.

Basic redacted stage/error details are sufficient: app/provider version, failed stage and useful host/error context, without cookies, authorization data or signed URL queries. No diagnostic framework, remote telemetry, correlation-ID infrastructure or oncall system is required.

**Non-goals:** website/hosting; native iOS; macOS/Linux verification; every Android extension; DEX conversion/Android compatibility runtime; rich home feed; downloads/season ZIPs; accounts/sync; a new persistence system; generic plugin management, hot reload or independent provider updates. No desktop publication before release/update protection. An external player may diagnose a stream but cannot complete the intended embedded playback goal.

## Alternatives Weighed

| Route | Decision |
| --- | --- |
| Pinned source adaptation with direct registration | Preferred starting hypothesis for one provider, conditional on license, entry point, dependencies and browser feasibility. Avoid a general loader solely for this milestone. |
| Existing compatible upstream JAR | Reuse if located and verified against this API and required extractors; could reduce adaptation maintenance. Select one route after the spike. A JAR suffix/cross-platform flag is insufficient proof. |
| Load/rename Android `.cs3` | Rejected: cannot supply JVM bytecode or Android APIs. A future import path must explicitly reject unsupported artifacts; no import interface is needed now. |
| Android emulator | Existing fallback under windows/; does not satisfy native desktop objective. |
| Hosted service | Deferred: adds hosting/operations contrary to this local milestone. |
| Desktop browser | Conditional capability. Evaluate one supported Windows integration against real challenge/extractor behavior, lifecycle and resource cost. Opening an ordinary browser does not prove cookie/header handoff. |
| External playback | Diagnostic only; choose one embedded runtime using real extracted media and its transport/packaging requirements. Do not implement both paths. |

## Key Decisions & Staging

1. **Baseline/provenance:** build/run the existing desktop shell on Windows and record prerequisites/outcome. Inspect and pin Moviesmod entry/provider/extractors/dependencies; verify applicable license and attribution before adaptation/distribution. Record exact source commit or verified artifact identity, local changes and maintenance ownership. The Android artifact hash does not authenticate a new JVM build.
2. **Execution proof:** compile the smallest source/JAR route against this library, reuse its contracts and register once at app scope. Verify failed/repeated initialization cannot leave duplicate or partial ready state. Keep provider-specific changes isolated; avoid hot reload or a generic lifecycle framework.
3. **Provider/browser proof:** demonstrate real initialization, search, details and extraction outside the UI thread, with bounded stages and cancellation/stale-result handling. If verification is required, show its progress, cancel/expiry/failure states and resume only after proven handoff. Define host/domain-scoped cookies, consistent user-agent, required headers and cleanup in desktop-owned state; do not copy Android's global cookie clearing into a desktop profile. Explicitly report unsupported resolver capabilities. If blocked, record the failed stage rather than silently substitute another provider.
4. **Embedded playback proof:** choose one runtime using actual Moviesmod sources. Carry metadata and headers/referer through manifests, segments, redirects and related requests as required. Verify startup, seek where supported, source switching and cleanup; record tested media types/hosts/codecs. Add a localhost proxy only if direct playback demonstrably cannot meet transport requirements.
5. **Interface/acceptance:** build the minimum journey around proven operations. Record a real movie and available series episode through search, details, selection and playback; leave any untested acceptance item open. Include cancellation and a recoverable failure. Add small deterministic checks for new nontrivial boundaries, including failed/repeated registration and unsupported-browser/HTML results. Keep fixture checks separate from live availability; neither replaces actual playback evidence.
6. **Android/distribution:** preserve common APIs, manifest schemas, Android application/signing identity and existing tag parsing. Run relevant shared/Android compilation/unit checks and release-script tests when affected; the current release workflow records its signed-build checks. Before publication verify Windows packaging, separate `desktop-vX.Y.Z` tags/workflow and platform-aware update discovery, including old APK behavior. Keep desktop releases out of overall latest selection while legacy clients depend on it; recover accidental selection changes by restoring a complete Android release as latest. A bundled app/provider rollback is sufficient initially.

### Reviewer status and provenance

All six reviewers completed before synthesis, using fresh conversation contexts (`fork_turns: none`). Each read its exact persona, plugin docs/codex.md, the target brief and selected reading list, then returned findings read-only. The synthesizer persisted the verbatim reports below and scaled recommendations to the one-provider prototype.

| Reviewer | Lens | Status | Report |
| --- | --- | --- | --- |
| egm-design | Architecture/failures | completed | [Design](reviews/design.md) |
| egm-codebase-details | Compatibility/diagnostics | completed | [Codebase details](reviews/codebase-details.md) |
| egm-zoom-out | Simplicity/resource cost | completed | [Zoom out](reviews/zoom-out.md) |
| egm-comprehension | UX/errors | completed | [Comprehension](reviews/comprehension.md) |
| egm-implementation-readiness | Migration/testing/rollout | completed | [Readiness](reviews/implementation-readiness.md) |
| egm-structural | Long-term maintenance | completed | [Structural](reviews/structural.md) |

Host instruction-file exclusion remains unverified: fresh conversation context does not establish filesystem, instruction or memory isolation. No document standard was supplied; this is concept brainstorming, not full design/template compliance review.

No compilation, provider execution, media playback, dependency selection, implementation or publication occurred during this brainstorm. The unrelated root concepts_brief.md was preserved.
