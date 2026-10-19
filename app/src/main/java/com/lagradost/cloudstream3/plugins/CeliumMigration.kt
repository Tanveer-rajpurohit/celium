package com.lagradost.cloudstream3.plugins

/** A saved extension repository, reduced to the fields the migration reasons about. */
data class RepoEntry(val name: String, val url: String)

/** Snapshot of both stores, taken once at the start of [PersonalDefaults.initialize]. */
data class MigrationInput(
    val defaultsV1: Boolean,
    val defaultsV2: Boolean,
    val defaultsV3: Boolean,
    /** DataStore `celium_theme_v2`. */
    val celiumTheme: String?,
    /** Default prefs `app_theme_key`. */
    val appTheme: String?,
    /** DataStore `REPOSITORIES_KEY`. */
    val repositories: List<RepoEntry>,
)

/** Writes to perform. A null field means "leave unchanged". */
data class MigrationPlan(
    val setCeliumTheme: String?,
    val setAppTheme: String?,
    val repositories: List<RepoEntry>?,
    /** Run the v2 seed: section, sources, adult off, primary colour. */
    val seedV2: Boolean,
    /** Run the v1 "if absent" preferences block. */
    val seedV1Prefs: Boolean,
    val markV1: Boolean,
    val markV2: Boolean,
    val markV3: Boolean,
)

/** Pure Celium 6 defaults migration. No Android types, so it runs on the JVM. */
object CeliumMigration {
    const val V1_KEY = "celium_defaults_v1"
    const val V2_KEY = "celium_defaults_v2"
    const val V3_KEY = "celium_defaults_v3"

    const val CSX_URL = "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json"
    val CSX = RepoEntry("CSX (Megix)", CSX_URL)

    val DEFAULT_REPOSITORIES: List<RepoEntry> = listOf(
        RepoEntry("Phisher Repo", "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json"),
        RepoEntry("CXXX", "https://raw.githubusercontent.com/phisher98/CXXX/builds/CXXX.json"),
        RepoEntry("GizliKeyif", "https://raw.githubusercontent.com/Kraptor123/Cs-GizliKeyif/refs/heads/master/repo.json"),
        RepoEntry("CNC", "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json"),
        CSX,
    )

    fun plan(input: MigrationInput): MigrationPlan {
        // Rule 1
        val fresh = !input.defaultsV2 && input.celiumTheme == null
        var celiumTheme: String? = null
        var appTheme: String? = null
        var repos: List<RepoEntry>? = null

        // Rule 2: fresh installs start Light; a saved theme is left alone (Req 1.6).
        if (fresh) {
            celiumTheme = "Light"
            appTheme = "Light"
        }
        // Rule 3
        if (!input.defaultsV1) {
            repos = (input.repositories + DEFAULT_REPOSITORIES).distinctBy { it.url }
        }
        // Rule 4 (rule 5: no source, adult, or random-button writes here).
        if (!input.defaultsV3) {
            repos = ((repos ?: input.repositories) + CSX).distinctBy { it.url }
            // Existing install that rendered Dark under the old code default keeps Dark (Req 1.4).
            if (input.defaultsV2 && input.celiumTheme == null) celiumTheme = "Dark"
        }
        if (repos == input.repositories) repos = null

        return MigrationPlan(
            setCeliumTheme = celiumTheme,
            setAppTheme = appTheme,
            repositories = repos,
            seedV2 = !input.defaultsV2,
            seedV1Prefs = !input.defaultsV1,
            markV1 = !input.defaultsV1,
            markV2 = !input.defaultsV2,
            markV3 = !input.defaultsV3,
        )
    }

    // ---- Celium 7 (Req 7.6–7.8) ----
    const val V7_KEY = "celium_defaults_v7"

    /** internalNames of plugins the Celium 7 migration uninstalls (Req 7.6). */
    val V7_REMOVED_PLUGINS: Set<String> = setOf("PikashowProvider", "CNC Verse Mobile")

    /** Source ids dropped from the catalogue in Celium 7. */
    val V7_REMOVED_SOURCE_IDS: Set<String> = setOf("pika", "netmirror")

    /** Celium Feed id each Section falls back to. */
    const val CELIUM_FEED_ID = "celium"

    /** True when a saved plugin should be uninstalled by the Celium 7 migration. */
    fun isV7RemovedPlugin(internalName: String): Boolean = internalName in V7_REMOVED_PLUGINS

    /**
     * Remaps a saved Section source selection (Req 7.7). Returns the replacement id,
     * or null when the selection should be left unchanged.
     */
    fun remapV7Source(saved: String?): String? =
        if (saved != null && saved in V7_REMOVED_SOURCE_IDS) CELIUM_FEED_ID else null

    /** Model of the stores after [plan] is written, used by tests. */
    fun apply(input: MigrationInput, plan: MigrationPlan): MigrationInput = input.copy(
        defaultsV1 = input.defaultsV1 || plan.markV1,
        defaultsV2 = input.defaultsV2 || plan.markV2,
        defaultsV3 = input.defaultsV3 || plan.markV3,
        celiumTheme = plan.setCeliumTheme ?: input.celiumTheme,
        appTheme = plan.setAppTheme ?: input.appTheme,
        repositories = plan.repositories ?: input.repositories,
    )
}
