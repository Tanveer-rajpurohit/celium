# Celium 7.0.6

- Start playback when a usable source arrives and keep collecting fallback sources.
- Remove provider-attempt popups and decorative source labels.
- Separate source/video quality selection from audio and subtitle controls, show familiar resolution labels, and put extra controls in the More menu.
- Improve online subtitle selection, preserve subtitle file formats, and support choosing a subtitle file from an archive.
- Show available download quality and file size, keeping missing metadata marked unknown.
- Keep failed downloads visible and offer another source for the same movie or episode without losing playback position.
- Prevent download failures from being treated as cancellations; wait for old download cleanup before replacing a source.

Season ZIP downloads remain planned for a later version. Provider availability and missing quality/size metadata still depend on the source.

This update uses the same package ID and release signing certificate as 7.0.5. Users on the signed 7.0.5 release can install it as an update.

Developed with AI assistance. Validation: Android unit tests, shared JVM tests, release-packaging tests and a signed Android release build. Live device/provider checks were not run.
