package com.hotwire.fisiontv.networkqual.cert

/**
 * Friendly provider names for well-known DNS IPs, plus the operator's
 * preferred-server branding.
 *
 * Driven by a static map rather than a server-side enrichment because
 * the set of "well-known DNS" doesn't change quickly and shipping a
 * code update is cheaper than burning a contract bump. When this list
 * needs to expand or the operator's preferred-server labels change,
 * edit here and roll an APK — no server coordination required.
 *
 * Unknown IPs return null and the UI renders the IP without a label.
 */
object DnsProviders {
    private val byIp: Map<String, String> = mapOf(
        // Cloudflare
        "1.1.1.1" to "Cloudflare",
        "1.0.0.1" to "Cloudflare",
        "2606:4700:4700::1111" to "Cloudflare",
        "2606:4700:4700::1001" to "Cloudflare",
        // Google
        "8.8.8.8" to "Google",
        "8.8.4.4" to "Google",
        "2001:4860:4860::8888" to "Google",
        "2001:4860:4860::8844" to "Google",
        // Hotwire's preferred resolvers (operator branding — these are
        // Quad9 publicly; "Hotwire Primary/Secondary" is the customer-
        // facing framing per the v8 design notes).
        "9.9.9.9" to "Hotwire Primary",
        "149.112.112.112" to "Hotwire Secondary",
        // OpenDNS
        "208.67.222.222" to "OpenDNS",
        "208.67.220.220" to "OpenDNS"
    )

    fun name(ip: String): String? = byIp[ip]
}
