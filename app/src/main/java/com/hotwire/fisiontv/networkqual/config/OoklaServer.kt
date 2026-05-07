package com.hotwire.fisiontv.networkqual.config

/**
 * A single Ookla speedtest server endpoint.
 *
 * Field [id] is the canonical identifier the backend uses; do not derive it
 * from [host] at runtime. The legacy `/speedtest/random{N}x{N}.jpg` URL is
 * used because OoklaServer 2.11's modern `/download` returns 0 bytes — see
 * [downloadUrl].
 */
data class OoklaServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 8080,
    val secure: Boolean = true
) {
    val scheme: String = if (secure) "https" else "http"
    val baseUrl: String = "$scheme://$host:$port"

    fun downloadUrl(bytes: Long): String =
        "$baseUrl/speedtest/random4000x4000.jpg?x=${System.currentTimeMillis()}"

    val uploadUrl: String get() = "$baseUrl/speedtest/upload.php"
    val helloUrl: String get() = "$baseUrl/hello"

    init {
        require(id.isNotBlank()) { "OoklaServer.id must not be blank" }
        require(name.isNotBlank()) { "OoklaServer.name must not be blank" }
        require(host.isNotBlank()) { "OoklaServer.host must not be blank" }
        require(port in 1..65535) { "OoklaServer.port out of range: $port" }
    }
}

