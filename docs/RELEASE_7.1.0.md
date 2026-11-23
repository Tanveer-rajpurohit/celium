# Celium 7.1.0

- Cast to TVs: connect once to a DLNA TV, Kodi or a Chromecast, and every Play goes to it, with the next episode starting automatically and progress saved.
- Choose the TV's audio language from the phone. For videos with several languages built in ("Dual Audio"), Celium asks once which language the TV should play and remembers it; the Audio menu switches language at the same spot.
- Quality, Audio and Subtitles menus while casting; the Audio menu only lists languages that are actually available.
- Finding sources uses much less CPU: full speed only while a video is starting or switching, then two background threads once it plays.
- Every tab (Home, Movies, TV Shows, Anime) opens on the Celium Feed instead of the last source picked in that tab.
- The phone player's source list is replaced by a quality menu.
- Dudefilms links are hidden from every source.

This update uses the same package ID and release signing certificate as 7.0.6. Users on the signed 7.0.6 release can install it as an update.

Developed with AI assistance. Validation: Android unit tests (including a load test of source finding), shared JVM tests and dev builds on a phone casting to Kodi. Casting to a physical TV with the new audio choice was not tested.
