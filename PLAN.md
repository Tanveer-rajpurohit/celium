# Celium 6: current implementation and remaining verification
Updated 18 October 2026. Version 6.0.0-celium (70).

## Implemented
Celium 5 base: neutral themes, offline DM Sans, Celium Feed, provider collections, responsive details, saved/history, downloads and source settings on the existing adapter, extractor, transfer and playback engines.

Celium 6: Light default, accent colour picker, plugin popup guard, infinite scroll, quick-return header, random button, details selectors/recommendations/icon back/header image, live source switching, link fallback with highest quality first, player download button, visible download queue, no phantom download notification.

Source catalogue: 23 sources. Added AniKoto, HiAnime, Movies4u (Phisher), Moviesmod (CSX repo, https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json) and XNXX (CXXX, adult). Adult defaults OFF. AnimePahe and Porn36 stay excluded.

Windows support is a starter for the same APK in a configured Android emulator.

## Continue from here
1. Read VERIFICATION.md; fill in Celium 6 build results after the final build.
2. Install the APK on a real phone/emulator. Check every route, Light/Dark, accent, large text, rotation and back behavior.
3. Exercise movie, show, anime and opt-in adult flows, including the five new sources, source switching, fallback, subtitles, quality and downloads.
4. Measure scrolling, startup and search latency on a named device. Do not call it lag-free without measurements.
5. Test the Windows starter with a real AVD.
6. Remove further inherited classes only after reference analysis and compatibility tests.

## Invariants
Do not restore adult-on defaults, AnimePahe/Porn36, repository installation UI or all-source search. Preserve provider URLs/headers. Keep work in this celium folder.
