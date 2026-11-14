package app.krewd.data

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** How a connection's server was checked. */
enum class Verified { Pin, Authority, Plain }

/**
 * HTTPS to the Krewd server. Krewd makes its own certificate, so the app trusts it by its pin (the SHA-256 of the
 * certificate's public key, learned from the connect code or over an address a certificate authority vouches
 * for), never by a warning click. Any other server certificate must pass the normal authority check and host
 * name check, as for Tailscale or a public domain. Plain HTTP is not allowed at all (network_security_config).
 */
object Tls {
    fun pinOf(cert: Certificate): String =
        "sha256/" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded), Base64.NO_WRAP)

    private val system: X509TrustManager by lazy {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private val factories = ConcurrentHashMap<String, SSLSocketFactory>()

    private fun factory(pin: String): SSLSocketFactory = factories.getOrPut(pin) {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrust(pin)), null) }.socketFactory
    }

    fun open(url: String, pin: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection && pin.isNotBlank()) {
            conn.sslSocketFactory = factory(pin)
            conn.hostnameVerifier = HostnameVerifier { host, session ->
                val pinned = runCatching { pinOf(session.peerCertificates[0]) == pin }.getOrDefault(false)
                pinned || HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
            }
        }
        return conn
    }

    /**
     * Pairing a new phone that has no pin yet: any certificate is accepted for that one exchange, and the pin of the
     * one seen is recorded. Nothing secret goes over it (public keys and signatures), and the phone signs the pin it
     * saw, so the server refuses the pairing if someone in between showed another certificate.
     */
    class Capture {
        @Volatile var pin: String? = null
        @Volatile var trustedBySystem: Boolean = false
    }

    fun openCapturing(url: String, capture: Capture): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            val trust = object : X509TrustManager {
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    if (chain.isEmpty()) throw CertificateException("no certificate")
                    capture.pin = pinOf(chain[0])
                    capture.trustedBySystem = runCatching { system.checkServerTrusted(chain, authType) }.isSuccess
                }
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
                    throw CertificateException("not a server")
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            conn.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory
            conn.hostnameVerifier = HostnameVerifier { host, session ->
                !capture.trustedBySystem || HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
            }
        }
        return conn
    }

    /** After a request: was the server the pinned one, one an authority vouches for, or plain HTTP? */
    fun verifiedBy(conn: HttpURLConnection, pin: String): Verified {
        if (conn !is HttpsURLConnection) return Verified.Plain
        val leaf = runCatching { conn.serverCertificates.firstOrNull() }.getOrNull() ?: return Verified.Authority
        return if (pin.isNotBlank() && pinOf(leaf) == pin) Verified.Pin else Verified.Authority
    }

    private class PinnedTrust(private val pin: String) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            if (chain.isNotEmpty() && pinOf(chain[0]) == pin) return
            system.checkServerTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw CertificateException("not a server")

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }
}
