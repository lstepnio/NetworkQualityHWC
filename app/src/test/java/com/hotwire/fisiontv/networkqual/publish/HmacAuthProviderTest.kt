package com.hotwire.fisiontv.networkqual.publish

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HmacAuthProviderTest {

    @Test fun `empty secret returns null`() = runTest {
        val p = HmacAuthProvider(secret = "", deviceId = "dev-1", now = { 1_715_000_000_000L })
        assertThat(p.sign("GET", "/v1/cert-config", ByteArray(0))).isNull()
    }

    @Test fun `signature is deterministic for fixed clock`() = runTest {
        val p = HmacAuthProvider(secret = "topsecret", deviceId = "dev-1", now = { 1_715_000_000_000L })
        val first = p.sign("GET", "/v1/cert-config", ByteArray(0))
        val second = p.sign("GET", "/v1/cert-config", ByteArray(0))
        assertThat(first).isEqualTo(second)
    }

    @Test fun `header has expected shape`() = runTest {
        val p = HmacAuthProvider(secret = "topsecret", deviceId = "dev-1", now = { 1_715_000_000_000L })
        val header = p.sign("GET", "/v1/cert-config", ByteArray(0))
        assertThat(header).matches("HMAC-SHA256 t=1715000000,sig=[0-9a-f]{64}")
    }

    @Test fun `signature changes with body`() = runTest {
        val p = HmacAuthProvider(secret = "topsecret", deviceId = "dev-1", now = { 1_715_000_000_000L })
        val a = p.sign("POST", "/v1/certifications", "{\"x\":1}".toByteArray())
        val b = p.sign("POST", "/v1/certifications", "{\"x\":2}".toByteArray())
        assertThat(a).isNotEqualTo(b)
    }

    @Test fun `signature changes with device id`() = runTest {
        val a = HmacAuthProvider(secret = "k", deviceId = "dev-A", now = { 1_715_000_000_000L })
            .sign("GET", "/v1/cert-config", ByteArray(0))
        val b = HmacAuthProvider(secret = "k", deviceId = "dev-B", now = { 1_715_000_000_000L })
            .sign("GET", "/v1/cert-config", ByteArray(0))
        assertThat(a).isNotEqualTo(b)
    }

    @Test fun `signature changes with path`() = runTest {
        val p = HmacAuthProvider(secret = "k", deviceId = "d", now = { 1_715_000_000_000L })
        val a = p.sign("GET", "/v1/cert-config", ByteArray(0))
        val b = p.sign("GET", "/v1/app/version", ByteArray(0))
        assertThat(a).isNotEqualTo(b)
    }

    // Cross-impl parity (Android sign / backend Sign) is verified by an
    // end-to-end cert run against the dev server with V1_HMAC_SECRET set
    // on both sides, not by a hardcoded golden — the canonical-string
    // layout is the contract here, and the Go test suite already pins it.
}
