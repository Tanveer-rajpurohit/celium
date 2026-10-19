package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lagradost.cloudstream3.AutoDownloadMode
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.settings.extensions.REPOSITORIES_KEY
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.ui.celium.CeliumSources

/** Personal build defaults. Seed once so subsequent removals and opt-outs are respected. */
internal object PersonalDefaults {
    private const val VERSION_KEY = "celium_defaults_v1"

    private val repositories = arrayOf(
        RepositoryData("Phisher Repo", "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json"),
        RepositoryData("CXXX", "https://raw.githubusercontent.com/phisher98/CXXX/builds/CXXX.json"),
        RepositoryData("GizliKeyif", "https://raw.githubusercontent.com/Kraptor123/Cs-GizliKeyif/refs/heads/master/repo.json"),
        RepositoryData("CNC", CeliumSources.CNC),
    )

    fun initialize(context: Context, preferences: SharedPreferences) {
        if (!preferences.getBoolean("celium_defaults_v2", false)) {
            setKey("celium_section", "Movies")
            com.lagradost.cloudstream3.ui.celium.CeliumSection.entries.forEach { setKey("celium_source_${it.name}", "celium") }
            preferences.edit {
                putBoolean(context.getString(R.string.enable_nsfw_on_providers_key), false)
                putString(context.getString(R.string.primary_color_key), "White")
                putString(context.getString(R.string.app_theme_key), "Black")
                putBoolean("celium_defaults_v2", true)
            }
        }
        if (preferences.getBoolean(VERSION_KEY, false)) return
        val current = getKey<Array<RepositoryData>>(REPOSITORIES_KEY)?.toList().orEmpty()
        setKey(REPOSITORIES_KEY, (current + repositories.toList()).distinctBy { it.url })
        preferences.edit {
            val adultKey = context.getString(R.string.enable_nsfw_on_providers_key)
            if (!preferences.contains(adultKey)) putBoolean(adultKey, false)
            val downloadKey = context.getString(R.string.auto_download_plugins_key)
            if (!preferences.contains(downloadKey)) putInt(downloadKey, AutoDownloadMode.All.value)
            val appUpdateKey = context.getString(R.string.auto_update_key)
            if (!preferences.contains(appUpdateKey)) putBoolean(appUpdateKey, false)
            val mediaKey = context.getString(R.string.prefer_media_type_key)
            if (!preferences.contains(mediaKey)) {
                putStringSet(mediaKey, TvType.entries.map { it.ordinal.toString() }.toSet())
            }
            putBoolean(VERSION_KEY, true)
        }
    }
}
