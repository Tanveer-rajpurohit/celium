# Celium 7 handoff
Active code: this celium folder.

Version: 7.0.0-celium (71). Android debug application ID: app.celium.android.debug. Minimum API 23. Windows starter: existing Android emulator only.

## Celium 7 changes
- Home is a combined Movies + TV Shows feed (the Celium Feed) with a featured carousel of 3–4 titles.
- Movies and TV Shows are separate sections; each item lands in exactly one, with section-scoped search.
- Home provider priority: MultiMovies → CineStream → Moviesmod, then the rest in catalogue order; a missing provider falls back to the next ready one.
- Feed cache: shown instantly when under 6 hours old; every open refreshes in the background and overwrites the cache.
- Faster loading: providers load in parallel, shelves appear as they finish, 15 s per-provider timeout.
- Related videos deduplicated by title URL; MultiMovies paging and quick provider/category switching fixed.
- Layout size: Small (80%), Medium (default, Celium 6 sizes), Large (125%).
- Edge-to-edge system bars coloured with the theme background; player fullscreen unchanged.
- Sources: PikaShow and NetMirror Mobile removed (uninstalled by a one-time migration); CNC Verse, MovieBox and CineStream added. 24 sources.
- Celium icon and Celium name in user-visible text.

## Carried forward
- Light default, accent picker, offline DM Sans, infinite scroll, live source switching, link fallback, player downloads.
- History independent of resume state; existing playback/download engines retained.
- Adult OFF by default; AnimePahe and Porn36 excluded.
- Repositories: Phisher, CXXX, GizliKeyif, CNC, CSX.

See VERIFICATION.md. Celium 7 build results are pending the final build.

## Resume safely
Do not infer source playback or smooth frame rates from passing compilation. Nothing in Celium 7 has been verified on a device. No native Windows .cs3 runtime or built-in VPN was added.

Keep inherited ABI-sensitive code and the existing package/applicationId. Read PLAN.md before extending the app. Source policy and types are in the app's ui/celium/ package.
