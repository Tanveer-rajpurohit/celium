package com.lagradost.cloudstream3.ui.settings

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream4.viewmodels.GithubFile
import com.lagradost.cloudstream4.viewmodels.GithubRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object GithubReleases : GithubRepository {
    const val APK_USERNAME = "Tanveer-rajpurohit"
    const val APK_REPOSITORY = "celium"
    const val APK_PRERELEASE = "pre-release"
    const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"
    private const val UPDATE_MANIFEST = "celium-update.json"
    private val defaultHeaders = mapOf("Accept" to "application/vnd.github+json")

    @Serializable
    private data class GithubObject(val sha: String)

    @Serializable
    private data class GithubTag(
        @JsonProperty("object") @SerialName("object") val githubObject: GithubObject,
    )

    override suspend fun getSha(userName: String, repository: String, tag: String): String =
        app.get(
            "https://api.github.com/repos/$userName/$repository/git/ref/tags/$tag",
            headers = defaultHeaders,
        ).parsed<GithubTag>().githubObject.sha

    override suspend fun getRelease(
        userName: String,
        repository: String,
        prerelease: Boolean,
        prereleaseTag: String,
        contentType: String,
    ): GithubFile {
        val endpoint = if (prerelease) "tags/$prereleaseTag" else "latest"
        val response = app.get(
            "https://api.github.com/repos/$userName/$repository/releases/$endpoint",
            headers = defaultHeaders,
        )
        check(response.isSuccessful) {
            if (response.code == 404) "No Celium release has been published yet"
            else "Celium releases could not be checked. Please try again later."
        }
        val release = response.parsed<CeliumRelease>()
        val prefix = "https://github.com/$userName/$repository/releases/download/"
        val manifestAsset = release.assets.singleOrNull { it.name == UPDATE_MANIFEST }
            ?: error("This release does not contain a Celium update manifest")
        require(manifestAsset.downloadUrl.startsWith(prefix)) { "Unexpected update manifest location" }
        val manifest = app.get(manifestAsset.downloadUrl).parsed<CeliumUpdateManifest>()
        return release.updateFor(manifest, BuildConfig.APPLICATION_ID, prefix)
    }
}
