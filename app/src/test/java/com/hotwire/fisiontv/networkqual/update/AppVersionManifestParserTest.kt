package com.hotwire.fisiontv.networkqual.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [AppVersionManifestParser] — the JSON → typed-manifest
 * boundary. The data-class `init { require(...) }` block does most of
 * the heavy lifting; this suite locks in the field mapping plus a few
 * representative failures.
 */
class AppVersionManifestParserTest {

    private val goodJson = """
        {
          "schemaVersion": 1,
          "latestVersionName": "0.7.1",
          "latestVersionCode": 71,
          "minRequiredVersionCode": 68,
          "apkUrl": "https://certifier-api.gethotwired.com/v1/app/download/0.7.1.apk",
          "apkSizeBytes": 12345678,
          "apkSha256": "${"a".repeat(64)}",
          "signingCertSha256": "${"b".repeat(64)}",
          "releaseNotes": "Adjusted 4K-HDR tier thresholds; added ATL-3 server.",
          "publishedAt": "2026-05-09T18:00:00Z"
        }
    """.trimIndent()

    @Test fun `happy path parses every field`() {
        val m = AppVersionManifestParser.parse(goodJson)
        assertThat(m.schemaVersion).isEqualTo(1)
        assertThat(m.latestVersionName).isEqualTo("0.7.1")
        assertThat(m.latestVersionCode).isEqualTo(71)
        assertThat(m.minRequiredVersionCode).isEqualTo(68)
        assertThat(m.apkUrl).startsWith("https://")
        assertThat(m.apkSizeBytes).isEqualTo(12_345_678L)
        assertThat(m.apkSha256).isEqualTo("a".repeat(64))
        assertThat(m.signingCertSha256).isEqualTo("b".repeat(64))
        assertThat(m.releaseNotes).contains("4K-HDR")
        assertThat(m.publishedAt).isEqualTo("2026-05-09T18:00:00Z")
    }

    @Test fun `releaseNotes and publishedAt default to null when absent`() {
        val json = goodJson
            .replace("\"releaseNotes\": \"Adjusted 4K-HDR tier thresholds; added ATL-3 server.\",", "")
            .replace("\"publishedAt\": \"2026-05-09T18:00:00Z\"", "")
            .replace(",\n}", "\n}")
        val m = AppVersionManifestParser.parse(json)
        assertThat(m.releaseNotes).isNull()
        assertThat(m.publishedAt).isNull()
    }

    @Test fun `hex hashes are lowercased on parse`() {
        val json = goodJson
            .replace("a".repeat(64), "A".repeat(64))
            .replace("b".repeat(64), "B".repeat(64))
        val m = AppVersionManifestParser.parse(json)
        assertThat(m.apkSha256).isEqualTo("a".repeat(64))
        assertThat(m.signingCertSha256).isEqualTo("b".repeat(64))
    }

    @Test fun `minRequiredVersionCode greater than latestVersionCode is rejected`() {
        val json = goodJson.replace("\"minRequiredVersionCode\": 68", "\"minRequiredVersionCode\": 99")
        val t = runCatching { AppVersionManifestParser.parse(json) }.exceptionOrNull()
        assertThat(t).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `short sha256 is rejected`() {
        val json = goodJson.replace("a".repeat(64), "abc")
        val t = runCatching { AppVersionManifestParser.parse(json) }.exceptionOrNull()
        assertThat(t).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `empty apkUrl is rejected`() {
        val json = goodJson.replace(
            "\"apkUrl\": \"https://certifier-api.gethotwired.com/v1/app/download/0.7.1.apk\"",
            "\"apkUrl\": \"\""
        )
        val t = runCatching { AppVersionManifestParser.parse(json) }.exceptionOrNull()
        assertThat(t).isInstanceOf(IllegalArgumentException::class.java)
    }
}
