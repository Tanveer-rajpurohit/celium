# Celium personal build notes

Current Celium is 7.0.0-celium (71) with 24 curated sources from the Phisher, CXXX, GizliKeyif, CNC and CSX repositories. For behaviour and build steps, use README.md, CELIUM_BUILD.md and CELIUM_STATUS.md.

## Defaults
- Repositories are seeded internally once per defaults version; no repository setup UI.
- Adult content is off until enabled in Settings.
- The Celium 7 migration runs once: it uninstalls PikaShow and NetMirror Mobile and resets any saved selection of them to the Celium Feed.
- Layout size defaults to Medium.

## Network settings
Settings → General → App proxy accepts `http://host:port` or `socks://host:port`. Credentials and URL paths are unsupported. Empty uses the device network, system proxy and VPN routing. Restart after changing it.

Configured proxies apply to the shared HTTP client, artwork and Media3 playback. Extensions with their own clients, WebViews, torrents or external players can behave differently. Use a device-level VPN when all traffic needs the same route.

## Compatibility
The extension API, result types, Android class loader and plugin registration contracts are unchanged. The Kotlin package and applicationId are unchanged. Android .cs3 extensions do not run natively on Windows.

The license and upstream attribution are in LICENSE and README.md.
