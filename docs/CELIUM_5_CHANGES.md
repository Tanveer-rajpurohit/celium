# Celium 5 implementation map
This update is built in the original workspace's celium folder. The Downloads/second-AI project and Reclaim were inspected only as references.

## New consumer screens
- CeliumScreen: compact browsing, dedicated Search, readable poster shelves, full collection grid and explicit pagination.
- CeliumDetailsFragment: new responsive detail page, movie actions, seasons, episode ranges/audio choices, saved status and download actions.
- CeliumLibraryFragment: Saved and History with item removal and confirmed clearing of visible history.
- CeliumDownloadsFragment: a new offline-library interface over the existing transfer engine, progress, play, pause, retry and deletion.
- CeliumSettingsFragment: light/dark/system theme, optional custom accent, Adult toggle, simple source controls and bounded download concurrency.
- CeliumTheme: neutral defaults, DM Sans bundled locally, shared typography and contrast-aware accent foreground.
- CeliumHistory: viewing history retained separately from resume state so completion does not erase it.

Both phone and wide-screen result destinations use the new details screen. Startup language/layout/extension onboarding is no longer routed. Single unlocked local-profile startup still uses existing safe account handling.

## Feed/search and compatibility
Celium Feed uses up to three ready sources and a small preview of their collections. It does not request hundreds of providers. Individual sources expose more collection sections incrementally and keep their own pagination.

ResultViewModel2, APIRepository, SearchResponse, LoadResponse, extractors and the download engine remain the domain/compatibility layer. URLs and headers are retained. The existing parallel downloader was not replaced; inspection found it matches the reference project's implementation.

Search ranking now runs away from the UI thread after debounce. Requests are canceled on scope changes, search has a deadline, and empty/repeated result pages end pagination. Exact URL duplicates and confidently matching title/year/type entries in the curated feed are removed; uncertain editions remain separate.

## Adult and startup
Adult is disabled initially, including a one-time migration from the previous adult-on build. Source policy gates loading, artifact downloads, updates and repository enumeration. Adult content is hidden from the new library/download/search surfaces when disabled. Source-policy notifications remain active even when Home is not visible.

AnimePahe is removed from the curated source policy and cannot be loaded by the new policy. Its exclusion does not promise that every other adapter will be fast.

## Player
The engine remains intact. Controls have a more compact center cluster, readable title, larger control targets, neutral timeline, clearer source/track labels and consistent typography. Decorative paused metadata panels are suppressed. Screen-reader exploration prevents automatic control hiding. The guessed fixed-duration opening skip is removed from the visible interface; the keyboard action uses real chapter navigation.

## Cleanup boundary
Removed the old Home customization dialog, three-color/density settings model, manual extension-install browsing controls, onboarding routing and discovery-provider injection. Replaced the normal legacy detail/library/download/settings routes.

Legacy engine classes and some old resource/screen definitions remain because shared runtime actions and TV/player code reference them. They were not deleted blindly. This is not a claim that every unused Cloudstream class has been removed or that all advanced legacy dialogs have been rewritten.

## Verification scope
Read the delivery verification report for actual build/test/lint results. Compilation cannot establish source availability, playback quality, scrolling frame rate or end-to-end device usability. No Android device was connected during the recorded device inventory.

