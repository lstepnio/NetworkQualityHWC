package com.hotwire.fisiontv.networkqual.publish

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs v1 requests with HMAC-SHA256 using a build-time shared secret
 * (BuildConfig.V1_HMAC_SECRET, fed from a CI env var).
 *
 * Canonical request — must match `internal/auth/hmac.go` on the server,
 * byte-for-byte. If you change the layout here, change it there.
 *
 *   <METHOD>\n
 *   <PATH>\n
 *   <X-Device-Id>\n
 *   <unix-seconds>\n
 *   <sha256-hex(body)>
 *
 * Header value: `HMAC-SHA256 t=<unix>,sig=<hex>`
 *
 * Empty `secret` → returns null (legacy passthrough; the server, also in
 * observe mode, will accept no header). Drop a non-empty value into
 * BuildConfig once both ends are wired up.
 */
class HmacAuthProvider(
    private val secret: String,
    private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis
) : AuthProvider {

    override suspend fun sign(method: String, path: String, body: ByteArray): String? {
        if (secret.isEmpty()) return null
        val t = now() / 1000L
        val bodyHashHex = sha256Hex(body)
        val canonical = buildString {
            append(method); append('\n')
            append(path); append('\n')
            append(deviceId); append('\n')
            append(t); append('\n')
            append(bodyHashHex)
        }
        val sig = hmacSha256Hex(secret, canonical)
        return "HMAC-SHA256 t=$t,sig=$sig"
    }

    companion object {
        fun sha256Hex(body: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(body).toHex()
        }

        fun hmacSha256Hex(secret: String, message: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
        }

        private fun ByteArray.toHex(): String {
            val sb = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xff
                sb.append(HEX[v ushr 4])
                sb.append(HEX[v and 0x0f])
            }
            return sb.toString()
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
