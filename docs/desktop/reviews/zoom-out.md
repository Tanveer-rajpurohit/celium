## Simplicity and resource cost review

The concept is appropriately narrow: one Windows client, one provider, one complete path to playback. Keep Moviesmod as the feasibility gate before committing to a broader desktop architecture.

1. **Major — Contrarian / YAGNI & Code Minimization: defer a general plugin loader.**  
   `BasePlugin` already registers `MainAPI` providers and extractors. Nothing in this milestone requires discovery, installation, unloading, or independent provider updates. A source-adapted Moviesmod provider registered directly through the existing APIs may be smaller than building and maintaining a JAR loader. Compare the two routes after inspecting the entry point and dependencies; do not make dynamic loading an acceptance requirement. A verified compatible upstream JAR could change that choice.

2. **Major — Contrarian / YAGNI & Code Minimization: browser support may dominate the project.**  
   The JVM `WebViewResolver` passes interceptor requests through and its terminal resolution method is unimplemented. The local search finds `CloudflareKiller` under Android `app/`, rather than in the reusable JVM library. The brief reports that Moviesmod uses it, so adapting source alone does not establish a working desktop provider. First prove the actual search/details/extraction requests and challenge behavior. If browser integration is necessary, evaluate one supported Windows route and its cookie/header handoff, runtime size, startup time, and memory before building the browsing interface. Do not silently remove challenge handling.

3. **Major — Contrarian / YAGNI & Code Minimization: choose playback through a real extracted stream.**  
   The desktop dependency list and entry point contain no media playback implementation. Avoid selecting an embedded player merely because it renders a sample URL. Compare available playback routes against Moviesmod output: required headers/referer, redirects, stream type, subtitles, and native runtime packaging. An installed external player could be a smaller proof route if it satisfies these requirements and the intended user experience; whether external playback satisfies the product goal needs agreement. Avoid implementing both routes.

4. **Major — Contrarian / YAGNI & Code Minimization: reuse contracts without porting Android orchestration.**  
   `MainAPI` already supplies `search`, `load`, and `loadLinks`; existing response models represent movies, series, and episodes. `ExtractorLink` already carries headers and referer and provides header composition. Reuse these values directly rather than introducing desktop-specific copies. Existing shared theme and controls are already used by `Main.kt`. Search also located Android `APIRepository`, but its location is a reuse lead, not evidence it can move unchanged. Keep initial desktop orchestration small and avoid migrating large Android view models merely to preserve their abstractions.

5. **Nit — Contrarian / YAGNI & Code Minimization: defer home-feed breadth and packaging breadth.**  
   Search → details → episode/source selection → playback is sufficient to demonstrate the requested core. A rich home feed, downloads, caching infrastructure, provider management, and additional operating-system installers add work before feasibility is established. Existing Compose configuration lists MSI, DMG, and DEB; this milestone needs Windows verification only. Retain the brief’s publication gate: the current release workflow publishes APK assets and marks the release latest, so desktop publication needs deliberate Android update compatibility.

**Recommended next decision:** approve a bounded Moviesmod compatibility and playback proof, followed by the smallest desktop interface that exposes its working stages. Record unsupported hosts and failure stages explicitly.

**Files actually read:** persona `agents/zoom_out.md`; plugin `docs/codex.md`; all eleven supplied project files: `docs/desktop/concepts_brief.md`, `docs/DESKTOP_PROVIDER_COMPATIBILITY.md`, `desktopApp/README.md`, desktop build file and `Main.kt`, shared/library build files, `BasePlugin.kt`, JVM `WebViewResolver`, release workflow, and `SELECTED_SOURCES.json`. Also inspected relevant portions of `MainAPI.kt` and search results from `ExtractorApi.kt` and connected shared/Android code.

**Unknowns:** upstream Moviesmod entry point, license, transitive compatibility, live browser requirement, player compatibility, and runtime costs remain unverified. Host instruction-file exclusion is unverified.

No files changed, compilation run, upstream source reverified, or playback tested. These are concept findings, not compatibility certification.
