package io.github.tieo.taghistory.apple.http

import kotlin.test.Test
import kotlin.test.assertTrue

/** The server signs in at gsa.apple.com, whose chain ends at Apple's legacy root. */
class AppleTrustTest {
    @Test
    fun apple_roots_are_trusted_next_to_the_jdk_roots() {
        val subjects = jdkAndAppleTrust.acceptedIssuers.map { it.subjectX500Principal.name }
        assertTrue(subjects.any { "CN=Apple Root CA," in it }, "legacy Apple Root CA missing")
        assertTrue(subjects.any { "CN=Apple Root CA - G2" in it })
        assertTrue(subjects.any { "CN=Apple Root CA - G3" in it })
        // The JDK's own roots still apply to every other host.
        assertTrue(subjects.size > 50)
    }
}
