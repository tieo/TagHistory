package io.github.tieo.taghistory.anisette

import io.github.tieo.taghistory.apple.http.HttpRequest
import io.github.tieo.taghistory.apple.http.HttpResponse
import io.github.tieo.taghistory.apple.http.HttpTransport
import io.github.tieo.taghistory.apple.plist.XmlPlist
import io.github.tieo.taghistory.apple.plist.anyToPlist
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [MacAnisetteProvider] against a fake CoreADI and a fake Apple MidService:
 * the machine is provisioned once, its identity rides on every header set,
 * and a saved machine is reused instead of registering a new Mac.
 */
@OptIn(ExperimentalEncodingApi::class)
class MacAnisetteProviderTest {

    /** CoreADI stand-in whose store is "provisioned" once provisioning finished. */
    private class FakeAdi(var store: ByteArray) : CoreAdi {
        var finished: Triple<ByteArray, ByteArray, Long>? = null
        var syncedSim: ByteArray? = null

        override fun isProvisioned() = store.decodeToString() == "provisioned"
        override fun headers(): AdiHeaders {
            check(isProvisioned()) { "not provisioned" }
            return AdiHeaders(mid = "MID".encodeToByteArray(), otp = "OTP".encodeToByteArray(), routing = 17106176)
        }
        override fun provisioningBegin(spim: ByteArray): AdiProvisioning {
            assertContentEquals("SPIM".encodeToByteArray(), spim)
            return AdiProvisioning("CPIM".encodeToByteArray(), session = 7)
        }
        override fun provisioningFinish(session: Int, ptm: ByteArray, tk: ByteArray, routing: Long) {
            assertEquals(7, session)
            finished = Triple(ptm, tk, routing)
            store = "provisioned".encodeToByteArray()
        }
        override fun sync(sim: ByteArray): AdiSync {
            syncedSim = sim
            return AdiSync("MID2".encodeToByteArray(), "SRM".encodeToByteArray())
        }
        override fun store() = store
    }

    private class MemoryStorage : MachineStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes
        override fun write(bytes: ByteArray) {
            this.bytes = bytes
        }
    }

    /** Apple's lookup and MidService, answering like the real ones. */
    private class FakeApple(private val syncEc: Long = 0) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            val body: Map<String, Any?> = when (request.url.substringAfterLast('/')) {
                "lookup" -> mapOf("urls" to URLS)
                "start" -> response(mapOf("spim" to Base64.encode("SPIM".encodeToByteArray())))
                "finish" -> response(
                    mapOf(
                        "ptm" to Base64.encode("PTM".encodeToByteArray()),
                        "tk" to Base64.encode("TK".encodeToByteArray()),
                        "X-Apple-I-MD-RINFO" to "17106176",
                    ),
                )
                "sync" -> response(mapOf("Status" to mapOf("ec" to syncEc, "em" to "refused")))
                else -> error("unexpected ${request.url}")
            }
            return HttpResponse(200, emptyMap(), XmlPlist.encode(anyToPlist(body)))
        }

        private fun response(r: Map<String, Any?>) = mapOf("Response" to r)

        companion object {
            val URLS = mapOf(
                "midStartProvisioning" to "https://gsa.example/start",
                "midFinishProvisioning" to "https://gsa.example/finish",
                "midSyncMachine" to "https://gsa.example/sync",
            )
        }
    }

    private fun provider(storage: MachineStorage, http: HttpTransport, opened: MutableList<FakeAdi>) =
        MacAnisetteProvider(
            openAdi = { _, store -> FakeAdi(store).also { opened += it } },
            storage = storage,
            http = http,
            nativeVersion = { "test" },
            clockMillis = { 1_790_000_000_000L },
            random = Random(42),
        )

    @Test
    fun a_new_machine_is_provisioned_once_and_its_identity_rides_on_the_headers() = runTest {
        val apple = FakeApple()
        val opened = mutableListOf<FakeAdi>()
        val p = provider(MemoryStorage(), apple, opened)

        val first = p.getHeaders()
        p.getHeaders()

        assertEquals(listOf("lookup", "start", "finish"), apple.requests.map { it.url.substringAfterLast('/') })
        val (ptm, tk, routing) = opened.single().finished!!
        assertContentEquals("PTM".encodeToByteArray(), ptm)
        assertContentEquals("TK".encodeToByteArray(), tk)
        assertEquals(17106176L, routing)

        val machine = p.machine()
        assertEquals(Base64.encode("OTP".encodeToByteArray()), first["X-Apple-I-MD"])
        assertEquals(Base64.encode("MID".encodeToByteArray()), first["X-Apple-I-MD-M"])
        assertEquals("17106176", first["X-Apple-I-MD-RINFO"])
        assertEquals(machine.deviceId, first["X-Mme-Device-Id"])
        assertEquals(Base64.encode(machine.localUserUuid.encodeToByteArray()), first["X-Apple-I-MD-LU"])
        assertEquals(machine.serial, first["X-Apple-I-SRL-NO"])
        assertTrue(machine.deviceId.startsWith("00006000-"), machine.deviceId)
        assertEquals(MacAnisetteProvider.CLIENT_INFO, machine.clientInfo)

        // MidService sees the same machine as later requests.
        val start = apple.requests[1]
        assertEquals(machine.deviceId, start.headers["X-Mme-Device-Id"])
        assertEquals(machine.localUserUuid, start.headers["X-Apple-I-MD-LU"])
        assertEquals(MacAnisetteProvider.CLIENT_INFO, start.headers["X-MMe-Client-Info"])
    }

    @Test
    fun a_saved_machine_is_reused_without_registering_a_new_mac() = runTest {
        val storage = MemoryStorage()
        provider(storage, FakeApple(), mutableListOf()).getHeaders()
        val before = provider(storage, FakeApple(), mutableListOf()).machine()

        val apple = FakeApple()
        val again = provider(storage, apple, mutableListOf())
        again.getHeaders()

        assertEquals(emptyList(), apple.requests, "a provisioned machine must not talk to MidService again")
        assertEquals(before, again.machine())
    }

    @Test
    fun a_resync_answers_apple_with_the_machines_srm() = runTest {
        val apple = FakeApple()
        val opened = mutableListOf<FakeAdi>()
        val p = provider(MemoryStorage(), apple, opened)
        p.getHeaders()
        apple.requests.clear()

        p.resync("SIM".encodeToByteArray())

        assertContentEquals("SIM".encodeToByteArray(), opened.single().syncedSim)
        val sync = apple.requests.last()
        assertEquals("https://gsa.example/sync", sync.url)
        val body = sync.body!!.decodeToString()
        assertTrue(Base64.encode("SRM".encodeToByteArray()) in body, body)
        assertTrue(Base64.encode("MID2".encodeToByteArray()) in body, body)
    }

    @Test
    fun a_refused_resync_fails_loudly() = runTest {
        val p = provider(MemoryStorage(), FakeApple(syncEc = -22421), mutableListOf())
        p.getHeaders()
        val e = assertFailsWith<AnisetteException> { p.resync("SIM".encodeToByteArray()) }
        assertTrue("-22421" in e.message.orEmpty(), e.message)
    }
}
