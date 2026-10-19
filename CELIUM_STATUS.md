# Celium 6 handoff
Active code: this celium folder. The Downloads copy and Reclaim were reference projects only.

Version: 6.0.0-celium (70). Android debug application ID: app.celium.android.debug. Minimum API 23. Windows starter: existing Android emulator only.

## Celium 6 changes
- Light is the default theme; accent colour picker in Settings.
- Plugin popup guard: no inherited plugin/repository dialogs in normal flows.
- Infinite scroll on browse; quick-return header; random title button.
- Details: source/season selectors, recommendations, icon back button, header image.
- Live source switching, link fallback and highest-quality first.
- Player download button; download queue visible; no phantom download notification.
- Full provider catalogue: 23 sources. New: AniKoto, HiAnime, Movies4u (Phisher), Moviesmod (CSX repo), XNXX (CXXX, adult).

## Carried forward
- Neutral themes, offline DM Sans, Celium Feed (up to three ready adapters), single-source search.
- History independent of resume state; existing playback/download engines retained.
- Adult OFF by default; AnimePahe and Porn36 excluded.
- Repositories: Phisher, CXXX, GizliKeyif, CNCVerse, CSX.

See VERIFICATION.md for build/test evidence and limitations. Celium 6 build results are pending the final build.

## Resume safely
Do not infer source playback or smooth frame rates from passing compilation. Nothing in Celium 6 has been verified on a device. No native Windows .cs3 runtime or built-in VPN was added.

Keep inherited ABI-sensitive code until dependencies are verified. Read PLAN.md before extending the app. Source policy and types are in app/src/main/java/com/lagradost/cloudstream3/ui/celium/.
