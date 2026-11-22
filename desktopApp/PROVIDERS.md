# Bundled Moviesmod source

Source: [SaurabhKaperwan/CSX](https://github.com/SaurabhKaperwan/CSX/tree/13903aa1c788a5cdd725b88d38bf2b9c1565a134/Moviesmod), commit `13903aa1c788a5cdd725b88d38bf2b9c1565a134`, module version 33, provider author megix.

The pinned upstream [README](third-party/CSX-README.md) explicitly licenses these extensions under GPL version 3 or later. The full GPL text is in the repository's [LICENSE](../LICENSE). Upstream states that maintenance is on hiatus. Celium maintainers own this desktop adaptation and must review upstream changes manually.

Adapted files: `MoviesmodProvider.kt` and `Utils.kt` under `src/jvmMain/kotlin/com/lagradost/cloudstream4/desktop/moviesmod/`.

Changes: relocated package; Moviesmod-only registration (Topmovies is outside this milestone); no Android Plugin/Context or CloudflareKiller; explicit suspended domain initialization; URL-encoded search; optional metadata failures retain provider details; cancellation propagates; URL-bearing debug output removed. Movie link-page requests are deferred to extraction, series link concurrency is limited, and the HTTP client uses HTTP/1.1 after a transport comparison. Desktop HTTP checks reject failed responses and recognized browser-verification pages.

A narrow Playwright/Edge integration handles JavaScript navigation from Moviesmod source pages in an isolated temporary browser context. It supports user-driven verification, bounded waits, cancellation, and cookie/user-agent handoff; this does not implement the general JVM browser resolver or establish end-to-end Moviesmod compatibility. Playwright Java 1.56.0 is provided by Microsoft under Apache-2.0; Edge is installed separately.

The source is compiled into the desktop app. No Android `.cs3` is loaded, and there is no generic desktop plugin installer. Android provider selection and release tooling are separate.

The initial player uses vlcj 4.12.1 (GPLv3-or-later, Caprica Software) and the user's installed matching-architecture VLC 3 runtime. Runtime bundling and installer distribution are not implemented yet.
