# Celium 5: current implementation and remaining verification
Updated 18 October 2026. This file supersedes the earlier adult-on, home-only redesign plan.

## Implemented
Neutral light/dark themes, offline DM Sans, optional readable custom accent, compact browsing and dedicated search, app-owned Celium Feed, individual provider collections with additional sections, responsive details, seasons and episode actions, saved/history management, download management and source settings.

The existing Android adapter, extractor, transfer and playback engines remain. Player controls are refined; decorative paused metadata and guessed opening skip are removed from the visible flow. Onboarding routes and the old Home customization/install controls are removed.

Adult defaults OFF with a one-time migration. AnimePahe and Porn36 are excluded from the selected source policy. Source loading/download/update gates respect the policy.

Windows support is a starter for the same APK in a configured Android emulator, not a native Windows extension runtime.

## Continue from here
1. Read [implementation map](docs/CELIUM_5_CHANGES.md), [research](docs/CELIUM_5_RESEARCH.md) and the latest VERIFICATION.md.
2. Install the new APK on a real phone/emulator. Verify every main route, large text, rotation and back behavior. Capture screenshots; no visual-device approval has been established by compilation.
3. Exercise movie, multi-season show, anime and opt-in adult source flows with authorized test items. Check subtitles, quality/audio selection, resume and download pause/retry/delete.
4. Measure scrolling frame timing, startup, first useful shelf and search latency on a named device/network. Do not call this lag-free without measurements.
5. Audit richer source capabilities and provider-specific search failures. MultiMovies adapter source was not present in the reviewed public master tree; do not claim an unperformed binary patch.
6. Test the Windows starter with a real configured AVD. Check cold boot, repeat launch, path spaces, multiple devices and data-preserving updates.
7. Only remove further inherited runtime classes after reference analysis and compatibility tests. Some legacy dialogs/resources remain intentionally; no blind deletion.

## Invariants
Do not restore adult-on defaults, AnimePahe startup loading, repository installation UI or all-source search. Preserve original provider URLs/headers and separate content metadata from playable availability. Keep work in this celium source folder; the Downloads copy is reference only.
