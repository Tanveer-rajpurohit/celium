package com.lagradost.cloudstream4.viewmodels

import com.lagradost.cloudstream4.AppSettings
import com.mihon.common.preference.InMemoryPreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GithubViewModelTest {
    private val now = 1_800_000_000_000L

    @Before fun setMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMainDispatcher() { Dispatchers.resetMain() }

    private class Releases(var version: Long = 77) : GithubRepository {
        val calls = Channel<Unit>(Channel.UNLIMITED)
        override suspend fun getRelease(userName: String, repository: String, prerelease: Boolean, prereleaseTag: String, contentType: String): GithubFile {
            calls.send(Unit)
            return GithubFile("sha256:" + "a".repeat(64), "https://example.com/celium.apk", "arbitrary APK title", "Changes", "v7.0.6", "release-77", version)
        }
        override suspend fun getSha(userName: String, repository: String, tag: String): String = "new-sha"
    }

    private val noInstaller = object : AppUpdater {
        override suspend fun update(settings: AppSettings, url: String, digest: DigestPair?, downloadProgress: (Long, Long?) -> Unit) {
            error("A check must never install automatically")
        }
    }

    private fun viewModel(settings: AppSettings, releases: Releases, installer: AppUpdater = noInstaller) = GithubViewModel(
        remoteUserName = "owner", remoteRepository = "celium", remotePrereleaseTag = "pre-release",
        remoteContentType = "application/vnd.android.package-archive", versionCode = 76,
        isPrerelease = false, buildSha = "old-sha", settings = settings,
        updater = installer, repository = releases, currentTimeMillis = { now },
    )

    @Test fun `offers only strictly newer version codes regardless of APK title`() = runBlocking<Unit> {
        for (version in listOf(75L, 76L, 77L)) {
            val releases = Releases(version)
            val model = viewModel(AppSettings(InMemoryPreferenceStore()), releases)
            model.onAction(GithubAction.SearchForUpdate)
            val state = withTimeout(5000) { model.state.first {
                it.dialog?.state is GithubUpdateDialogState.NoUpdateFound || it.dialog?.state is GithubUpdateDialogState.UpdateFound
            } }
            assertEquals(version > 76, state.dialog?.state is GithubUpdateDialogState.UpdateFound)
            assertTrue(state.dialog!!.isFromUser)
        }
    }

    @Test fun `daily automatic check is throttled but manual check bypasses interval`() = runBlocking<Unit> {
        val settings = AppSettings(InMemoryPreferenceStore())
        val releases = Releases()
        val model = viewModel(settings, releases)
        model.onAction(GithubAction.AutoSearchForUpdate)
        withTimeout(5000) { releases.calls.receive() }
        withTimeout(5000) { model.state.first { it.dialog?.state is GithubUpdateDialogState.UpdateFound } }
        assertEquals(now, settings.updates.lastAppUpdateCheck.get())
        model.onAction(GithubAction.AutoSearchForUpdate)
        assertTrue(releases.calls.tryReceive().isFailure)
        model.onAction(GithubAction.SearchForUpdate)
        withTimeout(5000) { releases.calls.receive() }
        withTimeout(5000) { model.state.first { it.dialog?.isFromUser == true && it.dialog?.state is GithubUpdateDialogState.UpdateFound } }
    }

    @Test fun `manual check bypasses skipped release`() = runBlocking<Unit> {
        val settings = AppSettings(InMemoryPreferenceStore())
        settings.updates.skipUpdate.set("release-77")
        val model = viewModel(settings, Releases())
        model.onAction(GithubAction.AutoSearchForUpdate)
        withTimeout(5000) { model.state.first { it.dialog?.state is GithubUpdateDialogState.NoUpdateFound } }
        model.onAction(GithubAction.SearchForUpdate)
        withTimeout(5000) { model.state.first { it.dialog?.state is GithubUpdateDialogState.UpdateFound } }
    }

    @Test fun `opt out blocks automatic checks while leaving manual check available`() = runBlocking<Unit> {
        val settings = AppSettings(InMemoryPreferenceStore())
        settings.updates.showAppUpdates.set(false)
        val releases = Releases()
        val model = viewModel(settings, releases)
        model.onAction(GithubAction.AutoSearchForUpdate)
        assertEquals(0L, settings.updates.lastAppUpdateCheck.get())
        assertTrue(releases.calls.tryReceive().isFailure)
        model.onAction(GithubAction.SearchForUpdate)
        withTimeout(5000) { model.state.first { it.dialog?.state is GithubUpdateDialogState.UpdateFound } }
    }

    @Test fun `checks again after a day or if device clock moves backwards`() = runBlocking<Unit> {
        for (lastCheck in listOf(now - 86_400_000L, now + 1000L)) {
            val settings = AppSettings(InMemoryPreferenceStore())
            settings.updates.lastAppUpdateCheck.set(lastCheck)
            val model = viewModel(settings, Releases())
            model.onAction(GithubAction.AutoSearchForUpdate)
            withTimeout(5000) { model.state.first { it.dialog?.state is GithubUpdateDialogState.UpdateFound } }
            assertEquals(now, settings.updates.lastAppUpdateCheck.get())
        }
    }

    @Test fun `failed installation after automatic discovery is visible to the user`() = runBlocking<Unit> {
        val model = viewModel(AppSettings(InMemoryPreferenceStore()), Releases())
        model.onAction(GithubAction.AutoSearchForUpdate)
        val found = withTimeout(5000) { model.state.first { it.dialog?.state is GithubUpdateDialogState.UpdateFound } }
        val file = (found.dialog!!.state as GithubUpdateDialogState.UpdateFound).file
        model.onAction(GithubAction.Update(file))
        val failure = withTimeout(5000) { model.state.first { it.dialog?.state is GithubUpdateDialogState.Error } }
        assertTrue(failure.dialog!!.isFromUser)
    }
}
