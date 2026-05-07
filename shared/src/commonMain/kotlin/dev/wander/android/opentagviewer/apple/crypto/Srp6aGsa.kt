package io.github.tieo.taghistory.apple.crypto

/**
 * SRP-6a client configured to match pysrp's
 * `rfc5054_enable() + no_username_in_x() + SHA-256 + NG_2048` — which
 * is exactly what Apple's GSA expects.
 *
 * Quirks that MUST match pysrp byte-for-byte (or M1/M2 won't line up):
 *  - `long_to_bytes(n)` is the MINIMUM big-endian encoding (no leading
 *    zero). Byte-length of A is non-deterministic (255 or 256 bytes).
 *  - `k = H(N || PAD(g, len(N)))` with PAD enabled (RFC 5054 mode).
 *  - `u = H(PAD(A, N_BYTES) || PAD(B, N_BYTES))`.
 *  - `HNxorg` pads g to len(N_bytes) before hashing.
 *  - `no_username_in_x=true` means `x = H(salt || H(":" || password))` —
 *    the inner hash's username slot is replaced with an empty string,
 *    represented by a leading `:` byte.
 */
class Srp6aGsa(
    private val username: String,
    private val rng: Rng = secureRng(),
) {
    private var a: BigInt? = null
    private var capitalA: BigInt? = null

    private var _sessionKey: ByteArray? = null
    private var _m1: ByteArray? = null
    private var expectedM2: ByteArray? = null

    /**
     * Generate `A = g^a mod N` and return its unpadded big-endian bytes.
     */
    fun startAuthentication(): ByteArray {
        if (a == null) {
            // pysrp's get_random_of_length(256) forces the top bit to 1
            // (see _pysrp.py). Copy the semantics — not a security property
            // the protocol depends on, but Apple's server accepts A either
            // way; test vectors depend on it.
            val buf = ByteArray(N_BYTES)
            rng.nextBytes(buf)
            buf[0] = (buf[0].toInt() or 0x80).toByte()
            a = bigIntFromBytes(buf)
            capitalA = G_2048.modPow(a!!, N_2048)
        }
        return capitalA!!.toMinimalBytes()
    }

    /**
     * Given server salt, server ephemeral B, and PBKDF2-derived password
     * bytes (per [GsaCrypto.encryptPassword]), compute M1. Throws if B
     * fails the SRP-6a safety check.
     */
    fun processChallenge(
        salt: ByteArray,
        serverB: ByteArray,
        pbkdfPassword: ByteArray,
    ): ByteArray {
        val capitalA = this.capitalA
            ?: throw IllegalStateException("startAuthentication() must be called first")
        val a = this.a!!

        val B = bigIntFromBytes(serverB)
        check(B.mod(N_2048).signum() != 0) { "SRP-6a safety check failed: B mod N == 0" }

        // k = H(N || PAD(g, len(N)))
        val k = bigIntFromBytes(
            sha256(
                N_2048.toMinimalBytes() +
                    padToLength(G_2048.toMinimalBytes(), N_BYTES)
            )
        )

        // u = H(PAD(A, N_BYTES) || PAD(B, N_BYTES))
        val aPadded = padToLength(capitalA.toMinimalBytes(), N_BYTES)
        val bPadded = padToLength(B.toMinimalBytes(), N_BYTES)
        val u = bigIntFromBytes(sha256(aPadded + bPadded))
        check(u.signum() != 0) { "SRP-6a safety check failed: u == 0" }

        // x = H(salt || H(":" || pbkdfPassword))  (no_username_in_x=true)
        val inner = sha256(byteArrayOf(':'.code.toByte()) + pbkdfPassword)
        val xBytes = sha256(salt + inner)
        val xInt = bigIntFromBytes(xBytes)

        // v = g^x mod N
        val v = G_2048.modPow(xInt, N_2048)

        // S = (B - k*v)^(a + u*x) mod N
        val base = (B - k * v).mod(N_2048)
        val exp = a + u * xInt
        val S = base.modPow(exp, N_2048)

        // K = SHA256(long_to_bytes(S)) — pysrp uses unpadded S bytes.
        val sBytes = S.toMinimalBytes()
        val sessionKey = sha256(sBytes)
        _sessionKey = sessionKey

        // M1 = H(HNxorg || H(I) || s || long_to_bytes(A) || long_to_bytes(B) || K)
        val hNxorg = hNxorG()
        val iHash = sha256(username.encodeToByteArray())
        val aBytes = capitalA.toMinimalBytes()
        val bBytes = B.toMinimalBytes()

        val m1 = sha256(hNxorg + iHash + salt + aBytes + bBytes + sessionKey)
        _m1 = m1

        // H_AMK = SHA256(long_to_bytes(A) || M1 || K) — server returns this as M2.
        expectedM2 = sha256(aBytes + m1 + sessionKey)
        return m1
    }

    /** Verify the server's M2 matches our expected `H(A || M1 || K)`. */
    fun verifySession(serverM2: ByteArray): Boolean {
        val expected = expectedM2
            ?: throw IllegalStateException("processChallenge() must be called first")
        return constantTimeEquals(expected, serverM2)
    }

    fun getSessionKey(): ByteArray? = _sessionKey?.copyOf()
    fun getM1(): ByteArray? = _m1?.copyOf()

    private fun hNxorG(): ByteArray {
        val hN = sha256(N_2048.toMinimalBytes())
        val hG = sha256(padToLength(G_2048.toMinimalBytes(), N_BYTES))
        val out = ByteArray(hN.size)
        for (i in hN.indices) out[i] = (hN[i].toInt() xor hG[i].toInt()).toByte()
        return out
    }

    companion object {
        /** RFC 5054 2048-bit group modulus. */
        private val N_2048 = bigIntFromString(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
                "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
                "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
                "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
                "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
                "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6" +
                "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
                "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73",
            16,
        )
        private val G_2048 = bigIntOf(2)
        private const val N_BYTES = 256

        private fun padToLength(src: ByteArray, length: Int): ByteArray {
            if (src.size >= length) return src
            val out = ByteArray(length)
            src.copyInto(out, destinationOffset = length - src.size)
            return out
        }

        private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
            return diff == 0
        }
    }
}
