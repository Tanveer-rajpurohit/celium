package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.settings.ApkUpdater
import com.lagradost.cloudstream3.ui.settings.GithubReleases
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.viewmodels.DigestPair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compatibility for the legacy settings screen; uses the same Celium release and installer. */
object InAppUpdater {
    suspend fun Activity.runAutoUpdate(checkAutoUpdate: Boolean = true): Boolean {
        val settings = AppSettings(this)
        if (checkAutoUpdate && !settings.updates.showAppUpdates.get()) return false
        val release = try {
            GithubReleases.getRelease(
                GithubReleases.APK_USERNAME, GithubReleases.APK_REPOSITORY,
                BuildConfig.FLAVOR == "prerelease", GithubReleases.APK_PRERELEASE,
                GithubReleases.APK_CONTENT_TYPE,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            withContext(Dispatchers.Main) {
                showToast("Could not check for Celium updates. Please try again later.", Toast.LENGTH_LONG)
            }
            // A handled error is not the same as "no update found" in legacy callers.
            return true
        }
        if (release.versionCode <= BuildConfig.VERSION_CODE) return false
        if (checkAutoUpdate && settings.updates.skipUpdate.get() == release.nodeId) return false
        withContext(Dispatchers.Main) {
            val builder = AlertDialog.Builder(this@runAutoUpdate, R.style.AlertDialogCustom)
                .setTitle(getString(R.string.new_update_format, BuildConfig.VERSION_NAME, release.displayName))
                .setMessage(release.changeLog)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.update) { _, _ ->
                    (this@runAutoUpdate as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope?.launch {
                        try {
                            showToast(R.string.download_started, Toast.LENGTH_LONG)
                            ApkUpdater.update(settings, release.downloadUrl, DigestPair.parse(release.digest)) { _, _ -> }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logError(e)
                            showToast(R.string.download_failed, Toast.LENGTH_LONG)
                        }
                    }
                }
            if (checkAutoUpdate) {
                builder.setNeutralButton(R.string.skip_update) { _, _ ->
                    settings.updates.skipUpdate.set(release.nodeId)
                }
            }
            builder.show()
        }
        return true
    }
}
