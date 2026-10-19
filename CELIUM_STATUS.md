# Celium 5 handoff
Active code: this celium folder. The Downloads copy and Reclaim were reference projects only.

Version: 5.0.0-celium (69). Android debug application ID: app.celium.android.debug. Minimum API 23. Windows starter: existing Android emulator only.

## Current behavior
- Neutral light/dark/system themes, offline DM Sans, optional accessible accent.
- New browse, search, responsive details, saved/history, downloads and Settings routes.
- Celium Feed uses up to three ready adapters; search stays scoped to one source.
- Individual provider collections expose more sections and pagination.
- History survives completion independently of resume state. Entries and visible history can be removed.
- Existing playback/download engines retained; player controls refined.
- Adult OFF by default; AnimePahe and Porn36 excluded.
- 18 retained artifact digests rechecked against SELECTED_SOURCES.json.

See VERIFICATION.md for the final build/test/lint evidence and limitations, and docs/CELIUM_5_CHANGES.md for implementation boundaries.

## Resume safely
Do not infer source playback or smooth frame rates from passing compilation. Device inventory had no connected Android device. No native Windows .cs3 runtime or built-in VPN was added. Linked open-source projects were researched, not imported.

Keep inherited ABI-sensitive code until dependencies are verified. Read PLAN.md before extending the app. Current source policy and types are in app/src/main/java/com/lagradost/cloudstream3/ui/celium/.
