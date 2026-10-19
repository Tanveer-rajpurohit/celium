# Celium 7: source findings
Implementation context, not a promise of source availability.

## Celium 7 source changes
The live plugins.json files of the Phisher, CNC and CSX repositories were checked when the Celium 7 spec was written:

| Source | internalName | Repository | Version |
| --- | --- | --- | --- |
| Netflix · Prime · Disney+ | CNC Verse | CNC | 114 |
| MovieBox | MovieBoxProvider | CNC (newer than Phisher's v37) | 52 |
| CineStream | CineStream | CSX | 487 |

PikaShow and NetMirror Mobile were removed. No plugin with "Simkl" in its name or description exists in these repositories, so CineStream holds the second Home priority slot. Package hashes for the new sources are unverified.

## Earlier references
| Project | Decision for Celium |
| --- | --- |
| [Streambert](https://github.com/truelockmc/streambert#streaming) | Adopt the separation of discovery and playback. No code copied. |
| [NsfwBox](https://github.com/Kisspeace/NsfwBox) | Reference for categorized integrations and downloads. No code imported. |
| [Stash](https://github.com/stashapp/stash#stash) | Possible future option for owned personal media. |

Open-source licenses do not grant permission to access or redistribute the media listed by a source. Celium adds no DRM, access-control or CAPTCHA bypass.

## MultiMovies
The selected binary adapter is the compatibility path; its source is not in the public Phisher tree, so Celium does not patch it. Celium 7 fixes app-side paging (load near the end, append only new titles) and stale results on quick switching.

## Network
A VPN is a transport choice, not a fix for a parser or category list. Celium includes no VPN service.

## Windows
The starter runs the Android APK in an existing Android emulator. It is not a native Windows .cs3 runtime. SDK, emulator and AVD are not bundled.
