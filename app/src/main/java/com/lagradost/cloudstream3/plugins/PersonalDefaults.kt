package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lagradost.cloudstream3.AutoDownloadMode
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.celium.CeliumSection
import com.lagradost.cloudstream3.ui.settings.extensions.REPOSITORIES_KEY
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData

/** Personal build defaults. Seed once so subsequent removals and opt-outs are respected. */
internal object PersonalDefaults {
    private const val CELIUM_THEME_KEY = "celium_theme_v2"

    fun initialize(context: Context, preferences: SharedPreferences) {
        val appThemeKey = context.getString(R.string.app_theme_key)
        val adultKey = context.getString(R.string.enable_nsfw_on_providers_key)

        // Snapshot both stores once; the plan is computed from this snapshot only.
        val savedRepos = getKey<Array<RepositoryData>>(REPOSITORIES_KEY)?.toList().orEmpty()
        val input = MigrationInput(
            defaultsV1 = preferences.getBoolean(CeliumMigration.V1_KEY, false),
            defaultsV2 = preferences.getBoolean(CeliumMigration.V2_KEY, false),
            defaultsV3 = preferences.getBoolean(CeliumMigration.V3_KEY, false),
            celiumTheme = getKey<String>(CELIUM_THEME_KEY),
            appTheme = preferences.getString(appThemeKey, null),
            repositories = savedRepos.map { RepoEntry(it.name, it.url) },
        )
        val plan = CeliumMigration.plan(input)

        // DataStore writes first; markers are written last so an interrupted run re-plans safely.
        if (plan.seedV2) {
            setKey("celium_section", "Movies")
            CeliumSection.entries.forEach { setKey("celium_source_${it.name}", "celium") }
        }
        plan.setCeliumTheme?.let { setKey(CELIUM_THEME_KEY, it) }
        plan.repositories?.let { repos ->
            // Keep existing entries as saved (including icon URLs).
            val byUrl = savedRepos.associateBy { it.url }
            setKey(REPOSITORIES_KEY, repos.map { byUrl[it.url] ?: RepositoryData(it.name, it.url) }.toTypedArray())
        }

        preferences.edit {
            if (plan.seedV2) {
                putBoolean(adultKey, false)
                putString(context.getString(R.string.primary_color_key), "White")
            }
            plan.setAppTheme?.let { putString(appThemeKey, it) }
            if (plan.seedV1Prefs) {
                if (!plan.seedV2 && !preferences.contains(adultKey)) putBoolean(adultKey, false)
                val downloadKey = context.getString(R.string.auto_download_plugins_key)
                if (!preferences.contains(downloadKey)) putInt(downloadKey, AutoDownloadMode.All.value)
                val appUpdateKey = context.getString(R.string.auto_update_key)
                if (!preferences.contains(appUpdateKey)) putBoolean(appUpdateKey, false)
                val mediaKey = context.getString(R.string.prefer_media_type_key)
                if (!preferences.contains(mediaKey)) {
                    putStringSet(mediaKey, TvType.entries.map { it.ordinal.toString() }.toSet())
                }
            }
            if (plan.markV1) putBoolean(CeliumMigration.V1_KEY, true)
            if (plan.markV2) putBoolean(CeliumMigration.V2_KEY, true)
            if (plan.markV3) putBoolean(CeliumMigration.V3_KEY, true)
        }
    }
}
