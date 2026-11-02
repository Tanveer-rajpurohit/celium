package com.lagradost.cloudstream3

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.ui.settings.ApkUpdater
import com.lagradost.cloudstream3.ui.settings.CeliumRelease
import com.lagradost.cloudstream3.ui.settings.CeliumReleaseAsset
import com.lagradost.cloudstream3.ui.settings.CeliumUpdateManifest
import com.lagradost.cloudstream4.viewmodels.DigestPair
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.DigestException
import java.security.MessageDigest

class CeliumUpdateTest {
    private val packageId = "app.celium.android.debug"
    private val prefix = "https://github.com/Tanveer-rajpurohit/celium/releases/download/"
    private val apkName = "celium-7.0.5-celium.apk"
    private val checksum = "a".repeat(64)
    private val manifest = CeliumUpdateManifest(1, packageId, 76, "7.0.5-celium", apkName, checksum)
    private val release = CeliumRelease("v7.0.5", "release-76", listOf(
        CeliumReleaseAsset("sources.zip", prefix + "v7.0.5/sources.zip"),
        CeliumReleaseAsset(apkName, prefix + "v7.0.5/" + apkName),
    ), "Fixes and improvements")

    @Test fun `selects the manifest APK even when a source archive is first`() {
        val update = release.updateFor(manifest, packageId, prefix)
        assertEquals(prefix + "v7.0.5/" + apkName, update.downloadUrl)
        assertEquals(76L, update.versionCode)
        assertEquals("7.0.5-celium", update.displayName)
        assertEquals("sha256:$checksum", update.digest)
        assertEquals("Fixes and improvements", update.changeLog)
    }

    @Test fun `GitHub response decoding tolerates an empty changelog`() {
        val mapper = jacksonObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val decoded = mapper.readValue<CeliumRelease>("""{
            "tag_name":"v7.0.5", "node_id":"release-76", "body":null,
            "target_commitish":"master", "assets":[
                {"name":"$apkName","browser_download_url":"${prefix}v7.0.5/$apkName", "size":123}
            ]
        }""")
        assertEquals("", decoded.updateFor(manifest, packageId, prefix).changeLog)
        val decodedManifest = mapper.readValue<CeliumUpdateManifest>("""{
            "schemaVersion":1,"applicationId":"$packageId","versionCode":76,
            "versionName":"7.0.5-celium","apkName":"$apkName","sha256":"$checksum"
        }""")
        assertEquals(manifest, decodedManifest)
    }

    @Test fun `rejects updates for another package or unsupported manifest`() {
        assertThrows(IllegalArgumentException::class.java) {
            release.updateFor(manifest, "app.celium.android", prefix)
        }
        assertThrows(IllegalArgumentException::class.java) {
            release.updateFor(manifest.copy(schemaVersion = 2), packageId, prefix)
        }
        assertThrows(IllegalArgumentException::class.java) {
            release.updateFor(manifest.copy(sha256 = "invalid"), packageId, prefix)
        }
    }

    @Test fun `rejects missing APKs and downloads outside the Celium release`() {
        assertThrows(IllegalStateException::class.java) {
            release.copy(assets = emptyList()).updateFor(manifest, packageId, prefix)
        }
        assertThrows(IllegalArgumentException::class.java) {
            release.copy(assets = listOf(CeliumReleaseAsset(apkName, "https://example.com/update.apk")))
                .updateFor(manifest, packageId, prefix)
        }
    }

    @Test fun `download must match SHA256 before installation`() = runBlocking<Unit> {
        val bytes = "APK payload".toByteArray()
        val digest = DigestPair("sha256", MessageDigest.getInstance("SHA-256").digest(bytes))
        val output = ByteArrayOutputStream()
        ApkUpdater.transfer(output, ByteArrayInputStream(bytes), bytes.size.toLong(), { _, _ -> }, digest)
        assertArrayEquals(bytes, output.toByteArray())
        try {
            ApkUpdater.transfer(ByteArrayOutputStream(), ByteArrayInputStream("damaged".toByteArray()), null, { _, _ -> }, digest)
            fail("A corrupted APK must not reach the installer")
        } catch (_: DigestException) {
            // Expected: fail closed instead of silently ignoring the checksum.
        }
    }
}
