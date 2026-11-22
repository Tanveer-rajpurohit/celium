## Long-Term Maintenance Brainstorm

**Assessment:** The narrow Windows milestone is maintainable if Moviesmod integration stays isolated and browser/player work remains in JVM-specific code. The brief correctly rejects DEX conversion and defers the website. Its main unresolved debt is ownership of provider adaptation and runtime lifecycle.

### 1. Major — Provider adaptation needs a defined update boundary

**Dimension:** Long-Term Tech Debt & Maintainability

`SELECTED_SOURCES.json` identifies an Android `.cs3` artifact, version 33, with a hash. That does not identify the source revision used by a future JVM adaptation. A locally copied provider can drift from upstream while still appearing to represent the selected artifact. The brief requests revision, license, attribution, and a change record, which is sound, but does not yet assign maintenance ownership or distinguish desktop compatibility from Android artifact compatibility.

**Maintainability requirement:** Before choosing the execution route, record the exact source commit or verified JAR, required extractors and dependencies, adaptation changes, and who updates them. Keep provider-specific fixes out of shared registration and browsing code. For the first bundled provider, avoid building a general plugin update system unless independently required.

### 2. Major — Browser support could spread platform dependencies across shared code

**Dimension:** Long-Term Tech Debt & Maintainability

`WebViewResolver.jvm.kt` currently passes interceptor requests through and throws `TODO` in its terminal resolver method. Its public contract includes request interception, headers, scripts, timeout, and callbacks. Implementing only whichever behavior the first successful Moviesmod request happens to exercise risks a resolver that appears compatible while failing later extractors. Both `shared` and `library` serve Android and JVM targets, so importing browser dependencies into common source sets would enlarge Android’s maintenance surface.

**Maintainability requirement:** Keep browser runtime dependencies and lifecycle in JVM-specific code. Explicitly identify supported resolver behavior and return actionable unsupported-operation errors for gaps. Establish cancellation, timeouts, cookie/header transfer, and browser cleanup before claiming end-to-end support. Preserve Android implementations and contracts unless a shared change is demonstrably necessary.

### 3. Major — Registration and screen lifecycle can become coupled

**Dimension:** Long-Term Tech Debt & Maintainability

`BasePlugin.registerMainAPI` and `registerExtractorAPI` mutate global collections. `beforeUnload()` is a hook; this class does not itself remove registered entries or undo partial registration. The desktop entry point currently contains only remembered demo state. Registering providers during Compose recomposition, retrying a failed initialization indiscriminately, or unloading during active extraction could produce duplicate entries or inconsistent global state.

**Maintainability requirement:** Own provider initialization once at application scope, outside screen composition and the UI thread. Specify behavior after failed initialization and avoid hot reload for this milestone. Scope search/details/extraction jobs to their owning navigation state so cancellation prevents obsolete results from replacing the current selection. Do not add a generic lifecycle framework merely to support one bundled provider.

### 4. Major — Desktop publication must remain separate from Android release identity

**Dimension:** Long-Term Tech Debt & Maintainability

`celium-release.yml` responds to general version tags, builds signed APKs, and publishes the release as latest. `desktopApp/build.gradle.kts` separately hardcodes installer version `1.0.0` and configures multiple installer formats. Reusing the existing tag/publication path without a platform policy could couple desktop releases to Android release discovery.

**Maintainability requirement:** Keep the brief’s publication gate. Before publishing desktop artifacts, specify platform-specific version/tag and asset discovery behavior, including already-installed Android clients. Windows-only proof does not require maintaining macOS/Linux packaging now.

### Evidence and limits

Read in full: `docs/desktop/concepts_brief.md`, `docs/DESKTOP_PROVIDER_COMPATIBILITY.md`, `desktopApp/README.md`, `desktopApp/build.gradle.kts`, desktop `Main.kt`, `shared/build.gradle.kts`, `library/build.gradle.kts`, `BasePlugin.kt`, `WebViewResolver.jvm.kt`, `.github/workflows/celium-release.yml`, and `SELECTED_SOURCES.json`; also the supplied structural persona and plugin `docs/codex.md`.

No document standard was supplied; this is concept review, without formatting compliance claims. Upstream plugin entry point and license remain unverified after reported fetch failures. Host instruction-file exclusion is unverified.

Skipped compilation, execution, playback, and file changes as required. Actual provider/browser/player compatibility remains unknown.
