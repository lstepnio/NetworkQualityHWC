package com.hotwire.fisiontv.networkqual.cert

import com.google.common.truth.Truth.assertThat
import com.hotwire.fisiontv.networkqual.config.DnsPolicyConfig
import org.junit.Test

class DnsAssessorTest {

    private val assessor = DnsAssessor()

    @Test fun `all actual servers preferred yields allPreferred true and empty nonPreferred`() {
        val policy = DnsPolicyConfig(preferredServers = listOf("1.1.1.1", "8.8.8.8"))
        val verdict = assessor.assess(policy, actualServers = listOf("8.8.8.8", "1.1.1.1"))

        assertThat(verdict.allPreferred).isTrue()
        assertThat(verdict.nonPreferred).isEmpty()
        assertThat(verdict.configuredPreferred).containsExactly("1.1.1.1", "8.8.8.8").inOrder()
    }

    @Test fun `one non-preferred server flags allPreferred false and reports just that server`() {
        val policy = DnsPolicyConfig(preferredServers = listOf("1.1.1.1", "8.8.8.8"))
        val verdict = assessor.assess(policy, actualServers = listOf("1.1.1.1", "192.0.2.53"))

        assertThat(verdict.allPreferred).isFalse()
        assertThat(verdict.nonPreferred).containsExactly("192.0.2.53")
    }

    @Test fun `empty actual list is vacuously all preferred`() {
        // Operator semantics: a box with no DNS configured fails its
        // upstream DNS test on its own — the assessor must not double-flag.
        val policy = DnsPolicyConfig(preferredServers = listOf("1.1.1.1"))
        val verdict = assessor.assess(policy, actualServers = emptyList())

        assertThat(verdict.allPreferred).isTrue()
        assertThat(verdict.nonPreferred).isEmpty()
    }

    @Test fun `IPv6 strings round-trip through unchanged`() {
        // Matching is literal string-equality — no parsing, no
        // canonicalization. Whatever the OS surfaces in dnsServers is
        // what the operator must put in preferredServers.
        val policy = DnsPolicyConfig(
            preferredServers = listOf("2606:4700:4700::1111", "2001:4860:4860::8888")
        )
        val verdict = assessor.assess(
            policy,
            actualServers = listOf("2606:4700:4700::1111", "fe80::1")
        )

        assertThat(verdict.allPreferred).isFalse()
        assertThat(verdict.nonPreferred).containsExactly("fe80::1")
        assertThat(verdict.configuredPreferred)
            .containsExactly("2606:4700:4700::1111", "2001:4860:4860::8888").inOrder()
    }

    @Test fun `duplicate non-preferred actual servers are kept in nonPreferred`() {
        // Documented choice: we preserve multiplicity rather than dedup.
        // A config-management bug that stamps the same wrong DNS on
        // multiple interfaces should be visible to the operator
        // inspecting the row, not collapsed into a single entry.
        val policy = DnsPolicyConfig(preferredServers = listOf("1.1.1.1"))
        val verdict = assessor.assess(
            policy,
            actualServers = listOf("192.0.2.53", "1.1.1.1", "192.0.2.53")
        )

        assertThat(verdict.allPreferred).isFalse()
        assertThat(verdict.nonPreferred).containsExactly("192.0.2.53", "192.0.2.53").inOrder()
    }
}
