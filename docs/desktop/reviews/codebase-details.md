# Code compatibility and operational diagnostics concept review

**Mode:** BRAINSTORM, read-only.  
**Target:** `docs/desktop/concepts_brief.md`  
**Disposition:** The proposed scope is consistent with the inspected desktop code. Proceed with a compatibility spike before committing to a provider loading or browser architecture. The gaps below should become brief requirements; they do not require a hosted telemetry system.

## Findings

### Major — Browser fallback can report apparent extraction success

**Dimension:** Observability & Operational SRE Debuggability  
**Affected files:** JVM `WebViewResolver.jvm.kt`, `FileMoon.kt`, `Filesim.kt`.

**Verified facts:** The JVM resolver interceptor simply calls `chain.proceed(request)`; its actual resolving method throws `TODO`. FileMoon and Filesim pass the resolver as an interceptor, obtain the response URL, and treat a nonempty URL as input to M3U8 generation.

**Operational risk:** An unsupported browser fallback need not throw. It can pass through an ordinary page URL and produce misleading extraction results, obscuring why playback fails. This is a stronger failure mode than “resolver unavailable.”

**Mitigation requirement:** Distinguish HTTP extraction, browser fallback, validated media results, and playback start. Unsupported browser operations must produce an explicit capability error. A nonempty extracted URL alone must not count as successful media resolution. Add a small negative fixture demonstrating that an HTML page returned through the JVM fallback is not accepted as playable media.

### Major — The Android Cloudflare implementation cannot be reused directly

**Dimension:** Observability & Operational SRE Debuggability  
**Affected files:** Android `CloudflareKiller.kt`, JVM `WebViewResolver.jvm.kt`.

**Verified facts:** `CloudflareKiller` imports Android `CookieManager` and Android logging, clears WebView cookies during initialization, detects certain 403/503 responses, calls the browser resolver, and hands cookies plus a WebView user agent into HTTP requests. Its cookie cache is keyed by host.

**Inference:** Adapting provider imports alone will not supply equivalent behavior. The desktop route may need challenge handling and consistent cookie/user-agent handoff across HTTP extraction and playback.

**Operational risk:** “Search failed” could mean missing Android classes, an unresolved challenge, expired clearance, a provider domain change, or malformed page parsing.

**Mitigation requirement:** Record challenge-needed, challenge-started, challenge-completed/failed, timeout, and cancellation as distinct local outcomes. Identify the operation and host without logging cookies, authorization headers, or signed URL queries. Preserve the brief’s conditional browser decision: inspect and test live behavior before selecting the smallest supported integration.

### Major — Existing logging is insufficient for packaged desktop triage

**Dimension:** Observability & Operational SRE Debuggability  
**Affected files:** JVM `Log.kt`, common `ArchComponentExt.kt`, desktop `Main.kt`.

**Verified facts:** JVM logging prints to standard output; `logError` prints throwable messages and stack traces. The desktop entry point is a demo window with no diagnostic flow.

**Operational risk:** Users launching a packaged Windows app may have no useful way to retrieve console output. Generic exceptions do not identify whether failure occurred during initialization, search, details, episode loading, extraction, or playback.

**Mitigation requirement:** Provide a bounded local diagnostic record with an operation identifier, stage, elapsed time, provider revision, application version, extractor/host, and outcome. Make recent diagnostics accessible or copyable from the desktop interface. Show a concise stage-specific error with retry where useful. Redact query strings, cookies, authentication headers, and other sensitive values before persistence/export.

A local correlation identifier is sufficient for this milestone; distributed tracing, remote ingestion, dashboards, and oncall infrastructure would add unnecessary scope.

### Major — Existing safe-call helpers can hide cancellation and capability failures

**Dimension:** Observability & Operational SRE Debuggability  
**Affected files:** common `ArchComponentExt.kt`, JVM-common `ArchComponentExt.jvmCommon.kt`.

**Verified facts:** `safe`, `safeAsync`, `launchSafe`, and `safeApiCall` catch `Throwable`. Cancellation can be converted into `Resource.Failure`; `NotImplementedError` becomes a generic “operation is not implemented” failure. Other mappings combine several failures into generic network or compatibility messages.

**Operational risk:** Canceling a search can look like an application error; unsupported desktop capabilities can disappear into a generic failure. Stale results may reach the interface if operation lifetime is not managed above these helpers.

**Mitigation requirement:** The desktop operation boundary must preserve cancellation and reject stale results. Diagnostics must distinguish user cancellation, timeout, network failure, parser failure, unsupported capability, and player failure. Avoid a broad shared-helper refactor unless necessary; use the smallest desktop-specific boundary and run Android checks if common behavior changes.

### Major — Provider registration does not establish artifact compatibility

**Dimension:** Observability & Operational SRE Debuggability  
**Affected files:** `BasePlugin.kt`, Android `Plugin.kt`, inspected `PluginManager.kt` loading section, `SELECTED_SOURCES.json`.

**Verified facts:** `BasePlugin.load()` and registration helpers are reusable, but registration mutates global provider/extractor collections. Android loading uses `PathClassLoader`, Android context/resources, manifests, and source-policy checks. The selected Moviesmod artifact is `.cs3` version 33.

**Operational risk:** A desktop “plugin loaded” message could conceal an incompatible entry point, missing transitive class, partial registration, or extractor mismatch. Repeated retries could also leave duplicate/partial registrations unless initialization is controlled.

**Mitigation requirement:** Verify the selected source revision/artifact, entry point, dependencies, provider registration, and required extractor registration separately. Expose an explicit unsupported-artifact result for Android `.cs3`. Initialize once, outside the UI thread; verify that failed initialization cannot be reported as ready. Prefer direct source registration if the spike shows it is smaller than a new general-purpose JAR manager.

### Major — Player handoff needs independent evidence

**Dimension:** Observability & Operational SRE Debuggability  
**Affected file:** `ExtractorApi.kt`, desktop entry point/build configuration.

**Verified facts:** `ExtractorLink` carries URL, referer, headers, media type, and separate audio tracks. `getAllHeaders()` already merges referer while respecting an existing case-insensitive referer header. The desktop module currently has no playback implementation or player dependency.

**Operational risk:** Successful parsing or a populated source list says nothing about required headers reaching manifests, segments, or other media requests.

**Mitigation requirement:** Reuse the existing link data and header helper. Report extraction completion and player startup separately, including first-frame/start failure. Validate at least one real movie and one episode when available, with required headers/referer preserved. Document the media types actually tested rather than implying support for every type in the shared model.

### Major — Preserve Android publication as a separate operational boundary

**Dimension:** Observability & Operational SRE Debuggability  
**Affected files:** `.github/workflows/celium-release.yml`, desktop `build.gradle.kts`.

**Verified facts:** The existing workflow signs/tests/packages APKs on version tags and publishes the release as latest. Desktop packaging declares MSI/DMG/DEB targets with a separate hardcoded `1.0.0` package version. The current release workflow does not build desktop installers.

**Operational risk:** Publishing a desktop-only release into the existing latest-release channel could affect Android update discovery. Successful desktop compilation would not establish installer packaging or preserve Android behavior.

**Mitigation requirement:** Keep desktop development separate from APK publication. Before desktop publication, explicitly verify Android update discovery for already-installed clients and define desktop versioning/assets. Preserve the current Android signing/application-ID behavior and existing release checks. No desktop release pipeline is needed for the compatibility spike.

## Unknowns and limits

- Upstream Moviesmod plugin-entry source and LICENSE fetches failed in the supplied investigation. Their contents, license permissions, complete dependencies, and current entry-point compatibility are **not verified** here.
- Upstream provider/extractor behavior described in the brief is supplied investigation evidence, not independently fetched or executed by this reviewer.
- No supported JVM Moviesmod JAR has been established.
- No compile, dependency resolution, installation, live provider request, challenge, extraction, or playback was executed.
- No player/browser implementation has been selected; its runtime size, codec support, cancellation behavior, packaging, and header/cookie support remain unknown.
- Android update-discovery behavior was not fully inspected; only the release workflow and targeted update-related source searches were examined.
- Fresh conversation isolation does not prove host instruction-file exclusion; that remains unverified.

## Files actually read

Fully read:

- Plugin `agents/codebase_details.md`
- Plugin `docs/codex.md`
- `docs/desktop/concepts_brief.md`
- `docs/DESKTOP_PROVIDER_COMPATIBILITY.md`
- `desktopApp/README.md`
- `desktopApp/build.gradle.kts`
- `desktopApp/src/jvmMain/kotlin/com/lagradost/cloudstream4/Main.kt`
- `shared/build.gradle.kts`
- `library/build.gradle.kts`
- `library/src/commonMain/kotlin/com/lagradost/cloudstream3/plugins/BasePlugin.kt`
- `library/src/jvmMain/kotlin/com/lagradost/cloudstream3/network/WebViewResolver.jvm.kt`
- `library/src/jvmMain/kotlin/com/lagradost/api/ContextHelper.jvm.kt`
- `library/src/jvmMain/kotlin/com/lagradost/api/Log.kt`
- `library/src/commonMain/kotlin/com/lagradost/cloudstream3/mvvm/ArchComponentExt.kt`
- `library/src/jvmCommonMain/kotlin/com/lagradost/cloudstream3/mvvm/ArchComponentExt.jvmCommon.kt`
- `app/src/main/java/com/lagradost/cloudstream3/network/CloudflareKiller.kt`
- `app/src/main/java/com/lagradost/cloudstream3/plugins/Plugin.kt`
- `.github/workflows/celium-release.yml`
- `SELECTED_SOURCES.json`

Targeted excerpts/search results read:

- Android `PluginManager.kt`: loader and initialization section
- Common `ExtractorApi.kt`: `ExtractorLink`, header helper, compatibility constructors
- Common `extractors/FileMoon.kt` and `extractors/Filesim.kt`: browser fallback sections
- Android `SettingsUpdates.kt`, `ApkUpdater.kt`, `CeliumUpdateManifest.kt`: update-related search results

No files were written or compiled. The main remaining risk is mistaking provider registration or an extracted URL for working desktop playback.
