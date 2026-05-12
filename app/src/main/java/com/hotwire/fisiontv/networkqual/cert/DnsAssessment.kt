package com.hotwire.fisiontv.networkqual.cert

/**
 * Client-computed verdict on whether the STB's actual DNS nameservers
 * matched the cert-config's `dnsPolicy.preferredServers`.
 *
 * Frozen at cert time — re-configuring the policy later does not
 * retroactively change this assessment on a historical payload.
 *
 * Semantics:
 *  - [configuredPreferred] is the round-trip of the active policy at the
 *    instant the cert ran. The dashboard renders the verdict from the
 *    payload alone (no re-resolve of the live cert-config).
 *  - [nonPreferred] is every entry in `network.dnsServers` NOT in the
 *    preferred set. Duplicates in the actual list are preserved — see
 *    [DnsAssessor] for the rationale.
 *  - [allPreferred] is true iff [nonPreferred] is empty (vacuously true
 *    when the actual list is empty).
 */
data class DnsAssessment(
    val configuredPreferred: List<String>,
    val nonPreferred: List<String>,
    val allPreferred: Boolean
)
