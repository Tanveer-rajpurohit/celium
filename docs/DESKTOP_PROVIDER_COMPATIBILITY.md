# Desktop provider compatibility investigation

Investigated 2026-11-03. This is a source-code investigation, not a desktop playback test.

## Finding

Celium's selected provider downloads use `.cs3`, not `.ex3` or `.e3` (see `SELECTED_SOURCES.json`). The obstacle is Android runtime compatibility, not the Kotlin language. Our desktop module also uses Kotlin, compiled for the JVM.

## Confirmed local blockers

| Location | Evidence | Desktop consequence |
| --- | --- | --- |
| `app/src/main/java/com/lagradost/cloudstream3/plugins/PluginManager.kt` | Loads plugins with `dalvik.system.PathClassLoader`, Android context, and optional Android resources | Cannot reuse this loader in a normal desktop JVM |
| `app/src/main/java/com/lagradost/cloudstream3/plugins/Plugin.kt` | Android `Context`, `Resources`, and Android action/settings hooks | Plugins using this interface require adaptation; changing the archive extension is insufficient |
| `library/src/commonMain/kotlin/com/lagradost/cloudstream3/plugins/BasePlugin.kt` | Platform-neutral registration of providers and extractors, plus `load()` | A reusable entry point exists for compatible providers |
| `library/src/jvmMain/kotlin/com/lagradost/cloudstream3/network/WebViewResolver.jvm.kt` | Interceptor passes requests through; final resolver method throws `TODO("Not yet implemented")` | Browser JavaScript/request interception is not implemented on desktop |
| `library/src/jvmMain/kotlin/com/lagradost/api/ContextHelper.jvm.kt` | `getContext()` returns null | Android context-dependent behavior is unavailable |
| `desktopApp/src/jvmMain/kotlin/com/lagradost/cloudstream4/Main.kt` | Demo controls; no provider-loading or media-playback flow | A desktop build does not yet establish provider compatibility |

Some existing extractors, including Filemoon and Filesim, call the WebView resolver as a fallback. A provider can load successfully and still fail at link extraction. Test these stages separately.

## Upstream evidence

- CloudStream's current [desktop entry point](https://github.com/recloudstream/cloudstream/blob/master/desktopApp/src/jvmMain/kotlin/com/lagradost/cloudstream4/Main.kt) is also a demo window. This finding applies to that upstream module, not every third-party desktop application bearing a similar name.
- Its extension [build tasks](https://github.com/recloudstream/gradle/blob/master/src/main/kotlin/com/lagradost/cloudstream3/gradle/tasks/Tasks.kt) package `classes.dex` and a manifest into `.cs3`; an optional separate JAR build exists.
- The [extension configuration](https://github.com/recloudstream/gradle/blob/master/src/main/kotlin/com/lagradost/cloudstream3/gradle/CloudstreamExtension.kt) exposes `isCrossPlatform`, intended for plugins without Android imports or app references.
- The [JAR task](https://github.com/recloudstream/gradle/blob/master/src/main/kotlin/com/lagradost/cloudstream3/gradle/tasks/CompilePluginJarTask.kt) produces a separate `.jar` when cross-platform support is enabled. Its presence alone does not prove a provider works with our current API and dependencies.

## Recommended first implementation

1. Build and run the existing desktop module.
2. Find one selected provider with a maintained cross-platform JAR. If none is available, adapt one provider from its source and build it against our library, preserving its license and attribution.
3. Add JVM loading for that compatible provider using `BasePlugin`; verify provider and extractor registration. Android `.cs3` files should produce an explicit unsupported-format message, not appear successfully installed.
4. Verify home, search, details, episode listing, and link/subtitle extraction before expanding the provider list. Carry required request headers and referer into playback.
5. Add desktop playback and verify the extracted media end to end. Providers needing a browser stay explicitly unsupported until a real desktop resolver is implemented and tested.

Prefer the existing upstream JAR route over starting with DEX conversion or an Android compatibility layer. Conversion alone cannot supply Android context, resources, or WebView behavior. Running the Android app in an emulator remains the existing fallback in `windows/`, not the native desktop implementation.

## Limits and future platforms

No provider archive was executed, no selected provider's current JAR availability was verified, and no desktop compilation or live playback was tested during this investigation. Compatibility must be established per provider; we cannot promise all 24 Android artifacts will work unchanged.

A JVM provider JAR also cannot be loaded directly by a browser or a native iOS runtime. Website and iOS provider execution need their own design after the desktop proof works.
