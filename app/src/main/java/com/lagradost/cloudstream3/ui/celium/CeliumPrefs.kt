package com.lagradost.cloudstream3.ui.celium

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android adapter for Celium settings stored in the default SharedPreferences.
 * The Random_Setting uses the legacy `random_button_key` (default false), so fresh installs
 * start OFF and upgraded installs keep their saved value (Requirement 12.1, 12.2, 12.3).
 */
object CeliumPrefs {
    private val randomState = MutableStateFlow(false)

    // Strongly referenced: SharedPreferences holds listeners weakly.
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    private fun randomKey(context: Context): String = context.getString(R.string.random_button_key)

    /** Live value of the Random_Setting; updates without restart when it changes (Requirement 12.9). */
    @Synchronized
    fun randomButton(context: Context): StateFlow<Boolean> {
        if (listener == null) {
            val prefs = prefs(context)
            val key = randomKey(context)
            val l = SharedPreferences.OnSharedPreferenceChangeListener { p, changed ->
                // changed == null means the prefs were cleared (API 30+).
                if (changed == null || changed == key) randomState.value = p.getBoolean(key, false)
            }
            listener = l
            prefs.registerOnSharedPreferenceChangeListener(l)
            randomState.value = prefs.getBoolean(key, false)
        }
        return randomState.asStateFlow()
    }

    fun setRandomButton(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(randomKey(context), value) }
        randomState.value = value
    }
}
