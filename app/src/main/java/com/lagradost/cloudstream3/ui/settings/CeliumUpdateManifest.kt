package com.lagradost.cloudstream3.ui.settings

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream4.viewmodels.GithubFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Generated from the built APK, not from a filename or the release title. */
@Serializable
internal data class CeliumUpdateManifest(
    val schemaVersion: Int,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val apkName: String,
    val sha256: String,
) {
    fun validateFor(installedApplicationId: String) {
        require(schemaVersion == 1) { "Unsupported Celium update format" }
        require(applicationId == installedApplicationId) { "This release is for a different Celium package" }
        require(versionCode > 0 && versionName.isNotBlank()) { "Invalid Celium update version" }
        require(apkName.endsWith(".apk") && '/' !in apkName && '\\' !in apkName) { "Invalid APK name" }
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Missing or invalid APK checksum" }
    }
}

@Serializable
internal data class CeliumReleaseAsset(
    val name: String,
    @JsonProperty("browser_download_url") @SerialName("browser_download_url") val downloadUrl: String,
)

@Serializable
internal data class CeliumRelease(
    @JsonProperty("tag_name") @SerialName("tag_name") val tagName: String,
    @JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
    val assets: List<CeliumReleaseAsset>,
    val body: String? = null,
) {
    fun updateFor(manifest: CeliumUpdateManifest, applicationId: String, assetPrefix: String): GithubFile {
        manifest.validateFor(applicationId)
        val apk = assets.singleOrNull { it.name == manifest.apkName }
            ?: error("The Celium APK listed in the update manifest is missing")
        require(apk.downloadUrl.startsWith(assetPrefix)) { "Unexpected APK download location" }
        return GithubFile(
            digest = "sha256:${manifest.sha256}",
            downloadUrl = apk.downloadUrl,
            displayName = manifest.versionName,
            changeLog = body.orEmpty(),
            tagName = tagName,
            nodeId = nodeId,
            versionCode = manifest.versionCode,
        )
    }
}
