# UX and error-handling brainstorm

The concept proposes a native Windows client built from `desktopApp`, starting with Moviesmod and proving search through actual playback locally. The existing desktop entry point is a demo; `BasePlugin` supplies registration hooks, while the JVM browser resolver passes intercepted requests through and throws `TODO` when explicit browser resolution is requested. The brief correctly treats compatibility and playback as unproven.

## Prioritized recommendations

1. **Major — Dimension: UX & Developer Ergonomics — Make provider readiness visible.**  
   **Usability friction:** A window that opens successfully could appear ready while provider initialization, domain lookup, or browser handling has failed. “Moviesmod supported” is too broad when search works but extraction does not.  
   **Ergonomic requirement:** Define startup states: initializing, ready, and failed with retry. Keep initialization off the UI thread. Show the failed operation in ordinary language, such as “Moviesmod could not initialize,” with diagnostic details available separately. Do not make users understand JAR versus `.cs3` for the default one-provider flow.

2. **Major — Dimension: UX & Developer Ergonomics — Specify recovery at each stage.**  
   **Usability friction:** “Actionable stage-specific errors” is a useful goal but does not yet define behavior. Empty results, failed requests, parser failures, unsupported browser requirements, and playback failures need different outcomes.  
   **Ergonomic requirement:** Distinguish confirmed empty results from unsuccessful searches; failed extraction from a successful response containing no playable sources; and player startup failure from extraction failure. Offer retry for the failed operation and return to the previous usable screen. Preserve the query, selected title, episode, and source list when an operation fails. Avoid claiming a provider outage unless evidence establishes one.

3. **Major — Dimension: UX & Developer Ergonomics — Define browser-challenge interaction before promising completion.**  
   **Usability friction:** Browser support is conditional, but Moviesmod’s reported Cloudflare use could make it essential to the initial journey. Users otherwise encounter an indefinite spinner or unexplained failure.  
   **Ergonomic requirement:** If observed requests require verification, explain what the user must do, expose cancellation, and report whether verification completed or expired. Resume the pending operation only when the chosen integration supports the required cookie/header handoff. Until then, display an explicit unsupported verification message. Opening an ordinary browser alone does not establish a working integration.

4. **Major — Dimension: UX & Developer Ergonomics — Make cancellation and navigation concrete.**  
   **Usability friction:** Cancellation is in scope without defined behavior. Slow initialization and overlapping searches can leave stale results or reopen playback after the user has left.  
   **Ergonomic requirement:** Require visible progress for network stages, bounded waits, and cancellation on navigation or replacement searches. Cancel must stop application-owned work where possible and prevent late results from changing the current screen. Back should restore the previous browsing context.

5. **Major — Dimension: UX & Developer Ergonomics — Specify the minimum usable playback journey.**  
   **Usability friction:** “Source selection” and “actual playback” leave unclear how users choose between similar links or recover from an incompatible stream.  
   **Ergonomic requirement:** Label sources using available host, quality, and language metadata without inventing missing values. For series, expose available season/episode information before extraction. Provide play/pause, seek where supported, volume, fullscreen, and return to source selection after failure. Verify required headers reach the player. Include keyboard navigation, visible focus, accessible control names, and usable window resizing; the current entry point disables the default focus outline.

6. **Major — Dimension: UX & Developer Ergonomics — Turn the proof into reproducible acceptance evidence.**  
   **Usability friction:** A successful compile or extracted URL can be mistaken for a usable desktop client.  
   **Ergonomic requirement:** Record Windows build/run prerequisites and verify a movie and an available series episode through search, details, source selection, and playback. Include one cancellation and one recoverable failure. Keep desktop publication behind platform-specific release/update separation; the inspected workflow publishes Android APK releases as latest.

## Reading and limits

Read the target brief, both supplied plugin instruction files, and all ten selected repository files: compatibility investigation, desktop README/build/entry point, shared and library builds, `BasePlugin`, JVM `WebViewResolver`, release workflow, and `SELECTED_SOURCES.json`.

No document standard was supplied. Upstream `Moviesmod.kt` and license contents remain unverified; brief statements about upstream behavior were treated as reported evidence. Host instruction-file exclusion remains unverified.

Skipped compilation, writes, provider execution, and playback tests under the read-only assignment. Browser/player feasibility and real Moviesmod behavior remain the principal unknowns.
