package io.github.tieo.taghistory.apple.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Desktop and server = CIO engine. gsa.apple.com serves a chain that ends
 * at Apple's legacy root, which the JDK trust store does not carry, so
 * Apple's three roots are trusted next to the JDK's (the same roots the
 * Android app bundles for Apple's domains).
 */
actual fun createPlatformHttpClient(): HttpClient = HttpClient(CIO) {
    followRedirects = false
    engine {
        https { trustManager = jdkAndAppleTrust }
    }
}

internal val jdkAndAppleTrust: X509TrustManager by lazy {
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    val jdk = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().single()
    jdk.acceptedIssuers.forEachIndexed { i, cert -> store.setCertificateEntry("jdk-$i", cert) }
    val factory = CertificateFactory.getInstance("X.509")
    for (name in listOf("apple_root_ca", "apple_root_ca_g2", "apple_root_ca_g3")) {
        val stream = checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("apple-roots/$name.cer")) {
            "apple-roots/$name.cer is missing from the classpath"
        }
        store.setCertificateEntry(name, stream.use { factory.generateCertificate(it) as X509Certificate })
    }
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(store) }
        .trustManagers.filterIsInstance<X509TrustManager>().single()
}
