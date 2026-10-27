package io.github.tieo.taghistory.anisette

/** Fixed anisette for one made-up Mac; counts header and resync calls. */
open class FakeAnisetteProvider(
    private val headers: Map<String, String> = linkedMapOf(
        "X-Apple-I-MD" to "md-value",
        "X-Apple-I-MD-M" to "mdm-value",
    ),
) : AnisetteProvider {
    var headerCalls = 0
    val resyncs = mutableListOf<ByteArray>()

    override suspend fun getHeaders(): Map<String, String> {
        headerCalls++
        return headers
    }

    override suspend fun machine() = MACHINE

    override suspend fun resync(sim: ByteArray) {
        resyncs += sim
    }

    override suspend fun version() = "test-version"

    companion object {
        val MACHINE = MachineIdentity(
            deviceId = "00006000-0123456789ABCDEF",
            localUserUuid = "0123456789ABCDEF0123456789ABCDEF",
            serial = "C02ABCDEFGHJ",
            clientInfo = MacAnisetteProvider.CLIENT_INFO,
        )
    }
}
