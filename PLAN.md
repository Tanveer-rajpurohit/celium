# Celium 7: current implementation and remaining verification
Version 7.0.0-celium (71).

## Implemented
Base (Celium 5–6): neutral themes, Light default, accent picker, offline DM Sans, Celium Feed, details, saved/history, downloads, live source switching, link fallback and source settings on the existing engines.

Celium 7: combined Home feed with featured carousel, Movies/TV Shows split, Home priority MultiMovies → CineStream → Moviesmod, 6-hour feed cache with background refresh, parallel provider loading, related-videos dedup, MultiMovies paging and switching fixes, Small/Medium/Large layout sizes, edge-to-edge system bars, Celium icon and text rebrand.

Source catalogue: 24 sources. Removed PikaShow and NetMirror Mobile. Added CNC Verse (Netflix · Prime · Disney+) and MovieBox from the CNC repo, and CineStream from the CSX repo. Adult defaults OFF. AnimePahe and Porn36 stay excluded.

Windows support is a starter for the same APK in a configured Android emulator.

## Continue from here
1. Read VERIFICATION.md; fill in Celium 7 build results after the final build.
2. Install the APK on a phone/emulator. Check Home, carousel, Movies/TV Shows split, layout sizes, system bars in Light/Dark, rotation and back behaviour.
3. Exercise CNC Verse, MovieBox and CineStream, plus the PikaShow/NetMirror migration on an existing install.
4. Measure scrolling and feed load time on a named device. Do not call it lag-free without measurements.
5. Test the Windows starter and bin launcher with a real AVD.

## Invariants
Do not restore adult-on defaults, AnimePahe/Porn36, PikaShow/NetMirror Mobile, repository installation UI or all-source search. Keep the package, applicationId and LICENSE. Preserve provider URLs/headers. Keep work in this celium folder.
