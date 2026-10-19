# Celium 7 implementation map
Version 7.0.0-celium (71).

## Home and sections
- Home is the Celium Feed: every main-page category of each pool provider, round-robin and incremental, combining Movies and TV Shows. Shelves are not split by section.
- Featured carousel: 4 titles when at least 4 are loaded, 3 when only 3, hidden below 3. Swipe, wrap-around previous/next, Play and Details controls with 48 dp targets.
- Home pool order: MultiMovies, CineStream, Moviesmod, then the remaining Movies/TV Shows sources in catalogue order. A provider that is not ready is backed by the next ready one; late providers are inserted at their position without moving the scroll.
- Movies and TV Shows: a classifier assigns each item to exactly one section using provider type, category hints and title hints. Unclassified items go to Movies. Search is scoped to the active section.

## Loading and cache
- Providers load in parallel; each shelf appears as soon as it finishes. Requests time out after 15 s.
- Feeds under 6 hours old show from cache instantly. Every open starts a background refresh that overwrites the cache; a failed refresh keeps the old cache.
- Switching provider or category cancels in-flight requests and discards stale results. MultiMovies pages load near the end of the list and append only new titles.
- Related videos show each title URL once, exclude the current title, and stop paging when a page adds nothing new.

## Layout and theme
- Layout size: Small (80% card width), Medium (default, Celium 6 sizes), Large (125%). Fonts and colours unchanged.
- Edge-to-edge system bars coloured with the theme background, icon contrast following Light/Dark, updated live. Player fullscreen keeps its immersive mode.
- Celium icon and Celium name in user-visible text. Package, applicationId and LICENSE unchanged.

## Sources
24 sources. Removed PikaShow (`PikashowProvider`) and NetMirror Mobile (`CNC Verse Mobile`). Added `CNC Verse` (Netflix · Prime · Disney+, CNC repo), `MovieBoxProvider` (CNC repo) and `CineStream` (CSX repo), available in Movies and TV Shows. A one-time Celium 7 migration uninstalls the removed plugins and resets saved selections to the Celium Feed. Sources that fail to load show as unavailable with a reason.

## Boundaries
Inherited engine classes and some resources remain because the player, TV and extension code reference them. Compilation does not establish source availability, playback, frame rate or device usability. See VERIFICATION.md.
