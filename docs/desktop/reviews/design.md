# Architecture concept review

The local Windows approach is coherent and avoids operating a provider service. The largest uncertainty is the first provider’s complete execution chain: compatible plugin entry point, challenge handling, extraction, and playback. Desktop UI work should follow a small end-to-end compatibility proof.

## Findings

### 1. Blocker — Provider feasibility remains unresolved

**Dimension:** Architecture and dependency cost  
**Scope:** Moviesmod execution route; `library/`; `desktopApp/`

The local API exposes provider and extractor registration through `BasePlugin`, but this does not establish that Moviesmod’s plugin entry point or dependencies can run on the JVM. The selected artifact is an Android `.cs3`; the upstream `Moviesmod.kt` entry point and license were not verified in this review. A source adaptation and a compatible JAR have different maintenance and packaging requirements.

**Mitigation requirement:** Make the first implementation gate a narrowly scoped Moviesmod compatibility spike. Verify source revision, entry point, dependency closure, license, desktop compilation, and initialization before building browsing screens. Prefer a bundled source adaptation if it proves smaller; do not introduce a general plugin manager solely to support one provider.

This blocks choosing an execution architecture, not further concept refinement.

### 2. Major — Browser support can dominate local resource cost

**Dimension:** Local memory, installation size, process lifecycle  
**Scope:** Challenge handling and `WebViewResolver.jvm.kt`

The JVM interceptor currently passes requests through, while the terminal resolver method throws `TODO`. Therefore neither successful plugin loading nor an ordinary HTTP request proves browser-dependent extraction works. The brief correctly treats a browser as conditional, but an eventual embedded browser could add substantial packaging and memory costs. No measured costs are available.

**Mitigation requirement:** Exercise real Moviesmod search and extraction early. If a browser is required, prove its challenge interaction and cookie, user-agent, and request-header handoff before selecting the runtime. Measure cold start, installer/download size, idle memory, and peak memory during playback on the intended Windows machine. Limit the initial implementation to one managed browser session, and close its resources on cancellation and app exit.

Do not promise a particular browser implementation or resource ceiling before measurements.

### 3. Major — Cancellation alone does not bound network work

**Dimension:** Request concurrency, local threads, upstream request load  
**Scope:** Search, initialization, extraction, source selection

The brief requires cancellation and notes upstream initialization reportedly performs blocking network access. Moving initialization off the UI thread protects responsiveness but does not automatically make blocking calls cancellable. Repeated searches, navigation, or source clicks could leave expensive work running and allow stale results to replace newer state.

**Mitigation requirement:** Require bounded initialization and stage-specific network deadlines, one active search/details request per displayed context, and cancellation when the context changes. Prevent repeated playback/extraction clicks from launching duplicate work. Ensure a late result cannot overwrite a newer selection. Confirm how the actual provider HTTP stack handles cancellation; do not equate coroutine cancellation with stopped network activity.

There is no evidence requiring a distributed queue, database, or service quota system.

### 4. Major — Playback compatibility needs a transport contract

**Dimension:** Playback integration and bandwidth  
**Scope:** Extractor output to desktop player

The shared extractor model already carries `referer` and headers. The brief includes playback with required headers, but it does not yet establish that the eventual desktop player can apply these to manifests, media segments, redirects, and associated requests. A source URL can resolve successfully and fail only after playback begins.

**Mitigation requirement:** Choose the player using actual extracted sources. Verify header/referer handling throughout playback, startup, seek, and at least one source switch. Exercise both a movie and an available episode as proposed. Preserve extraction metadata through source selection; report unsupported transport or codec behavior separately from provider failure.

Avoid adding a localhost proxy unless direct playback demonstrably cannot satisfy the required request behavior; a proxy adds another lifecycle and failure boundary.

### 5. Major — Provider retry must not duplicate global registrations

**Dimension:** In-process state and failure recovery  
**Scope:** `BasePlugin.registerMainAPI()` and `registerExtractorAPI()`

Both registration methods append to global collections. They do not provide transactional loading or deduplication. If initialization partially registers a provider or extractors and then fails, a UI retry could append duplicates. This is a foreseeable failure boundary for either source adaptation or a JAR loader.

**Mitigation requirement:** Define one-time provider ownership for the first milestone. A failed attempt must leave registration state consistent before retry. Prefer one explicit startup registration path over hot reload or runtime plugin replacement. If dynamic loading becomes necessary, specify rollback and unloading then.

### 6. Major — Android protection must include shared API and release boundaries

**Dimension:** Regression and distribution risk  
**Scope:** `shared/`, `library/`, `.github/workflows/celium-release.yml`

The desktop depends on both shared modules, each of which also configures Android targets. Desktop compatibility fixes in common code can affect Android independently of changes under `app/`. The release workflow publishes Android releases with `--latest`; `structure.md` explicitly warns that existing Android clients may depend on the overall latest release.

**Mitigation requirement:** Keep desktop-only integrations in JVM/desktop source sets. Run the existing affected Android checks whenever shared behavior changes. Preserve the Android tag workflow during the proof. Before publishing desktop, verify separate desktop tags/workflow and update discovery, including behavior of already-installed Android versions. The current brief already recognizes this gate and should retain it as a publication prerequisite.

## Economics and recommended scope

Local execution avoids hosted compute, database, storage, and media-egress infrastructure for this milestone. It transfers cost to each user’s CPU, memory, installation footprint, network, and upstream rate limits. There is no evidence for adding a backend, cloud database, telemetry service, or LLM dependency.

Keep the milestone to one provider, one managed execution lifecycle, one active playback session, and user-driven extraction. Measure browser/player costs rather than estimating unsupported numbers. Do not pre-extract every episode or source merely to populate screens.

## Files actually read

All project paths below resolve under `C:/Users/vyasg/Projects/celium/`.

- `docs/desktop/concepts_brief.md`
- `docs/DESKTOP_PROVIDER_COMPATIBILITY.md`
- `desktopApp/README.md`
- `desktopApp/build.gradle.kts`
- `desktopApp/src/jvmMain/kotlin/com/lagradost/cloudstream4/Main.kt`
- `shared/build.gradle.kts`
- `library/build.gradle.kts`
- `library/src/commonMain/kotlin/com/lagradost/cloudstream3/plugins/BasePlugin.kt`
- `library/src/jvmMain/kotlin/com/lagradost/cloudstream3/network/WebViewResolver.jvm.kt`
- `.github/workflows/celium-release.yml`
- `SELECTED_SOURCES.json`
- `structure.md`

Bundled instructions read:

- `C:/Users/vyasg/.codex/plugins/cache/egm/egm/0.1.0/agents/design_critic.md`
- `C:/Users/vyasg/.codex/plugins/cache/egm/egm/0.1.0/docs/codex.md`

Additional targeted search results inspected: updater references in `app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt`, and header/referer declarations in `library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt`. These were searches, not full-file reads.

## Unknowns and limits

No compilation, provider execution, playback, measurement, dependency selection, or writes were performed. Upstream entry-point and license fetching failed elsewhere and remain unverified; source assertions in the brief were treated as reported evidence rather than independently revalidated. Actual Cloudflare behavior, playback protocols, codecs, and minimum Windows resource requirements remain unknown.

Fresh-context instruction-file exclusion remains unverified, as the integration guidance requires; fresh conversation context alone does not establish that isolation.
