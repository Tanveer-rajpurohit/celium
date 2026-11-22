## Migration, testing, and rollout review

The concept is suitable for a desktop feasibility milestone. No existing desktop user-data migration is established by the inspected code. The main compatibility risks are Android update discovery, changes to shared provider APIs, and treating provider registration as proof of playback support.

### 1. Major — Migration & Backward Compatibility: desktop publication can break installed Android update checks

**Compatibility hazard:** `GithubReleases.kt` requests GitHub’s overall `releases/latest`, then requires `celium-update.json`. A desktop-only latest release would make existing Android clients fail update checks. Updating the Android updater alone does not protect previously installed APKs. The current Android workflow explicitly publishes with `--latest`; its packaging script requires `vX.Y.Z` tags.

**Migration requirement:** Retain the brief’s publication gate. Use separate desktop tags and release automation, and keep desktop releases out of GitHub’s overall latest selection while legacy Android clients rely on it. Preserve existing Android signing identity, application ID, manifest schema, and tag parsing. Before publication, test update discovery against both Android and desktop release records, including the legacy-client behavior. Document recovery by restoring a complete Android release as latest if publication selection changes accidentally.

This blocks unsafe publication, not local desktop implementation.

### 2. Major — Migration & Backward Compatibility: shared fixes can alter Android plugin behavior

**Compatibility hazard:** `desktopApp` consumes both `shared` and `library`. `BasePlugin.registerMainAPI()` and `registerExtractorAPI()` mutate shared registries; Android and JVM share additional library implementation through `jvmCommonMain`. Moving Android imports into common code or changing registration/API contracts to accommodate Moviesmod could break existing extensions.

**Migration requirement:** Keep desktop loading, browser integration, and playback in desktop/JVM implementations wherever possible. Preserve common signatures and serialized plugin manifest fields unless a separately justified migration is designed. If shared behavior changes, require Android compilation and relevant provider/API regression checks, alongside existing shared and Android tests. Test failed initialization and repeat initialization so partial registration or duplicate providers cannot survive a retry.

### 3. Major — Migration & Backward Compatibility: provider rollback needs a reproducible baseline

**Compatibility hazard:** The selected Moviesmod record describes an Android `.cs3` version and hash. Those identifiers do not authenticate a new source adaptation or prove compatibility with this library. A newer upstream provider or changed extractor can regress only one stage of the flow.

**Migration requirement:** Record the exact upstream source revision, local changes, required dependencies, and corresponding executable artifact identity for whichever route wins. Retain a reproducible previous working build and make provider failures diagnosable by stage. For the first milestone, a bundled provider and application rollback can satisfy this requirement; a dynamic plugin updater is unnecessary.

Source redistribution remains conditional on verifying applicable license and attribution requirements.

### 4. Major — Migration & Backward Compatibility: test evidence must cover actual runtime boundaries

**Compatibility hazard:** The desktop entry point is a demo, and the JVM WebView resolver passes intercepted requests through before ultimately reaching an unimplemented resolver method. Compilation, fixture parsing, and successful provider registration cannot establish usable Moviesmod support.

**Migration requirement:** Gate expansion on a Windows run demonstrating search → details → available episode selection → extraction → source selection → actual playback. Include a movie and a series episode when available; verify required headers/referer, cancellation, unavailable sources, and clear network/challenge errors. Exercise a real browser-dependent path if observed. Keep deterministic parser/contract checks separate from live-provider checks so network outages do not conceal app regressions.

### 5. Nit — Migration & Backward Compatibility: keep persistence out until needed

The current demo supplies no established desktop storage schema. Avoid importing Android preferences or plugin-install records implicitly. If browser cookies or settings are introduced, use desktop-owned storage and ensure reinstall/rollback preserves or safely ignores it.

### Evidence and limits

Read the target brief; all eleven selected repository paths; `structure.md`; Android `GithubReleases.kt`; `scripts/prepare_release.py`; and the supplied readiness persona and plugin `docs/codex.md`.

No writes, compilation, tests, provider execution, playback, or installer validation were performed. Upstream Moviesmod entry-point/license fetches remain unverified; host instruction-file exclusion is also unverified.
