package io.github.tieo.taghistory.anisette

/**
 * Produces anisette data for Apple GSA / iCloud requests: the headers that
 * prove a request comes from a machine Apple provisioned. The machine is a
 * Mac run by [MacAnisetteProvider]; Apple hands Find My tokens only to
 * macOS and iOS identities, and the token then works only together with the
 * anisette of the machine that obtained it.
 */
interface AnisetteProvider {
    /**
     * One-time password and machine id plus the machine's identity:
     * `X-Apple-I-MD`, `X-Apple-I-MD-M`, `X-Apple-I-MD-RINFO`,
     * `X-Apple-I-MD-LU`, `X-Mme-Device-Id`, `X-Apple-I-SRL-NO`,
     * `X-Apple-I-Client-Time`, `X-Apple-I-TimeZone`, `X-Apple-Locale`,
     * `loc`. Provisions the machine with Apple first if it never was.
     */
    suspend fun getHeaders(): Map<String, String>

    /** The machine the headers belong to. Provisions it first if it never was. */
    suspend fun machine(): MachineIdentity

    /**
     * Answers Apple's resync request (`hsc 434`), whose `X-Apple-I-MD-DATA`
     * is [sim]. Apple then accepts the machine's headers again.
     */
    suspend fun resync(sim: ByteArray)

    /** Underlying ottjni crate version. Useful as a preflight/canary. */
    suspend fun version(): String
}

/**
 * Who the provisioned machine claims to be. [clientInfo] is the
 * `X-MMe-Client-Info` its `akd` sends, `<model> <os;version;build> <bundle>`.
 */
data class MachineIdentity(
    val deviceId: String,
    val localUserUuid: String,
    val serial: String,
    val clientInfo: String,
) {
    /** `<model> <os;version;build>`, the part of [clientInfo] every Apple daemon shares. */
    val platform: String get() = clientInfo.substringBeforeLast(" <")

    /** Client info for another daemon on this machine, e.g. `com.apple.AOSKit/282 (com.apple.accountsd/113)`. */
    fun clientInfoFor(bundle: String): String = "$platform <$bundle>"
}
