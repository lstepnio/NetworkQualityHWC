package com.hotwire.fisiontv.networkqual.cert

import com.hotwire.fisiontv.networkqual.config.DnsPolicyConfig

/**
 * Compares the STB's actual DNS nameservers against the operator's
 * preferred-server policy, producing a [DnsAssessment] for the
 * certification payload.
 *
 * Matching is **strict** (every actual server must be in the preferred
 * list) and **literal** (string-equality, no CIDR / hostname /
 * wildcard). IPv4 and IPv6 strings round-trip through unchanged.
 *
 * Duplicate-handling: if `actualServers` contains the same non-preferred
 * address twice, both copies appear in [DnsAssessment.nonPreferred]. The
 * alternative (de-dup) hides multiplicity that may be meaningful to an
 * operator inspecting the row (e.g. a config-management bug stamping
 * the same wrong server on multiple interfaces). Keeping duplicates
 * preserves the input shape and keeps the implementation a one-liner.
 *
 * Empty `actualServers` is vacuously "all preferred" — a box with no
 * DNS configured fails upstream tests on its own and shouldn't be
 * double-flagged here.
 *
 * Pure function; no Android deps; fully unit-testable.
 */
class DnsAssessor {

    fun assess(policy: DnsPolicyConfig, actualServers: List<String>): DnsAssessment {
        val preferredSet = policy.preferredServers.toSet()
        val nonPreferred = actualServers.filter { it !in preferredSet }
        return DnsAssessment(
            configuredPreferred = policy.preferredServers,
            nonPreferred = nonPreferred,
            allPreferred = nonPreferred.isEmpty()
        )
    }
}
