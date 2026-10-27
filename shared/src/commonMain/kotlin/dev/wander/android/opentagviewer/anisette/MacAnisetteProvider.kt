package io.github.tieo.taghistory.anisette

import io.github.tieo.taghistory.apple.gsa.GSA_CONNECTION
import io.github.tieo.taghistory.apple.http.HttpRequest
import io.github.tieo.taghistory.apple.http.HttpTransport
import io.github.tieo.taghistory.apple.plist.PlistValue
import io.github.tieo.taghistory.apple.plist.XmlPlist
import io.github.tieo.taghistory.apple.plist.anyToPlist
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Anisette from Apple's own macOS CoreADI, which [CoreAdi] runs under
 * emulation, for a made-up Mac this provider registers with Apple once.
 *
 * Apple answers Grand Slam `init` with `-80045` for anisette from its Android
 * ADI, and issues `searchPartyToken` only to macOS and iOS identities, so the
 * machine has to be a Mac. Its identity (device id, local user, serial, the
 * host UUID CoreADI hashes into the machine id) and CoreADI's store are kept
 * in [storage]; a lost machine file makes this a new Mac, and Apple then asks
 * for the second factor again.
 *
 * Provisioning talks to Apple's `MidService` the way `akd` does, through
 * [http], which already trusts Apple's roots.
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class)
class MacAnisetteProvider(
    private val openAdi: (hostUuid: ByteArray, store: ByteArray) -> CoreAdi,
    private val storage: MachineStorage,
    private val http: HttpTransport,
    private val nativeVersion: () -> String,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val random: Random = Random.Default,
    /** Where the emulation and storage run; IO on the JVM, since every call blocks. */
    private val blockingContext: CoroutineContext = EmptyCoroutineContext,
) : AnisetteProvider {

    private val lock = Mutex()
    private var machine: MachineFile? = null
    private var adi: CoreAdi? = null
    private var provisionedChecked = false

    override suspend fun version(): String = nativeVersion()

    override suspend fun machine(): MachineIdentity = serialized { provisioned().first.identity() }

    override suspend fun getHeaders(): Map<String, String> = serialized {
        val (file, core) = provisioned()
        val headers = core.headers()
        save(file, core)
        linkedMapOf(
            "X-Apple-I-Client-Time" to isoTime(),
            "X-Apple-I-TimeZone" to "UTC",
            "loc" to LOCALE,
            "X-Apple-Locale" to LOCALE,
            "X-Apple-I-MD" to Base64.encode(headers.otp),
            "X-Apple-I-MD-LU" to Base64.encode(file.localUserUuid.encodeToByteArray()),
            "X-Apple-I-MD-M" to Base64.encode(headers.mid),
            "X-Apple-I-MD-RINFO" to headers.routing.toString(),
            "X-Mme-Device-Id" to file.deviceId.uppercase(),
            "X-Apple-I-SRL-NO" to file.serial,
        )
    }

    override suspend fun resync(sim: ByteArray) = serialized {
        val (file, core) = provisioned()
        val sync = core.sync(sim)
        save(file, core)
        val urls = urlBag(file)
        val request = linkedMapOf<String, Any?>(
            "srm" to Base64.encode(sync.srm),
            "X-Apple-I-MD-M" to Base64.encode(sync.mid),
        )
        file.routing?.let { request["X-Apple-I-MD-RINFO"] = it }
        val status = midService(file, urls.require("midSyncMachine"), request).dict("Status")
        val ec = status?.int64("ec")
        if (ec != 0L) {
            throw AnisetteException("Apple refused the machine's resync: ec=$ec ${status?.string("em").orEmpty()}")
        }
    }

    private suspend fun <T> serialized(block: suspend () -> T): T =
        lock.withLock { withContext(blockingContext) { block() } }

    /** The machine, loaded or created, and provisioned with Apple. */
    private suspend fun provisioned(): Pair<MachineFile, CoreAdi> {
        val file = machine ?: load().also { machine = it }
        val core = adi ?: openAdi(file.hostUuidBytes(), file.storeBytes()).also { adi = it }
        // Asking CoreADI costs an emulated session, so once is enough.
        if (!provisionedChecked) {
            if (!core.isProvisioned()) provision(file, core)
            provisionedChecked = true
        }
        return machine!! to core
    }

    private suspend fun provision(file: MachineFile, core: CoreAdi) {
        val urls = urlBag(file)
        val spim = midService(file, urls.require("midStartProvisioning"), emptyMap()).string("spim")
            ?: throw AnisetteException("midStartProvisioning gave no spim")
        val begun = core.provisioningBegin(Base64.decode(spim))
        val finished = midService(
            file,
            urls.require("midFinishProvisioning"),
            mapOf("cpim" to Base64.encode(begun.cpim)),
        )
        val ptm = finished.string("ptm") ?: throw AnisetteException("midFinishProvisioning gave no ptm")
        val tk = finished.string("tk") ?: throw AnisetteException("midFinishProvisioning gave no tk")
        val routing = when (val r = finished["X-Apple-I-MD-RINFO"]) {
            is PlistValue.Int64 -> r.value
            is PlistValue.Str -> r.value.toLongOrNull() ?: 0L
            else -> 0L
        }
        core.provisioningFinish(begun.session, Base64.decode(ptm), Base64.decode(tk), routing)
        val updated = file.copy(routing = routing, provisionedAtMs = clockMillis())
        machine = updated
        save(updated, core)
    }

    private fun load(): MachineFile {
        storage.read()?.let { bytes ->
            runCatching { JSON.decodeFromString(MachineFile.serializer(), bytes.decodeToString()) }
                .getOrNull()
                ?.takeIf { it.version == MachineFile.VERSION }
                ?.let { return it }
        }
        return mint().also { storage.write(JSON.encodeToString(MachineFile.serializer(), it).encodeToByteArray()) }
    }

    private fun save(file: MachineFile, core: CoreAdi) {
        val updated = file.copy(storeB64 = Base64.encode(core.store()))
        machine = updated
        storage.write(JSON.encodeToString(MachineFile.serializer(), updated).encodeToByteArray())
    }

    /** A new Mac in the shapes Apple's own client uses. */
    private fun mint(): MachineFile = MachineFile(
        version = MachineFile.VERSION,
        hostUuid = random.nextBytes(16).toHex(),
        deviceId = "00006000-" + random.nextBytes(8).toHex().uppercase(),
        localUserUuid = random.nextBytes(16).toHex().uppercase(),
        serial = "C02" + (1..9).map { SERIAL_ALPHABET[random.nextInt(SERIAL_ALPHABET.length)] }.joinToString(""),
        clientInfo = CLIENT_INFO,
    )

    private suspend fun urlBag(file: MachineFile): Map<String, String> {
        val response = http.execute(HttpRequest("GET", LOOKUP_URL, midServiceHeaders(file, clientTime = false)))
        if (!response.isOk()) throw AnisetteException("GSA lookup failed: HTTP ${response.statusCode}")
        val urls = (XmlPlist.parse(response.body) as? PlistValue.Dict)?.dict("urls")
            ?: throw AnisetteException("GSA lookup has no urls")
        return urls.entries.mapNotNull { (k, v) -> (v as? PlistValue.Str)?.let { k to it.value } }.toMap()
    }

    private suspend fun midService(file: MachineFile, url: String, request: Map<String, Any?>): PlistValue.Dict {
        val body = XmlPlist.encode(
            anyToPlist(linkedMapOf<String, Any?>("Header" to emptyMap<String, Any?>(), "Request" to request)),
        )
        val response = http.execute(HttpRequest("POST", url, midServiceHeaders(file, clientTime = true), body))
        if (!response.isOk()) throw AnisetteException("${url.substringAfterLast('/')} failed: HTTP ${response.statusCode}")
        return (XmlPlist.parse(response.body) as? PlistValue.Dict)?.dict("Response")
            ?: throw AnisetteException("${url.substringAfterLast('/')} gave no Response")
    }

    private fun midServiceHeaders(file: MachineFile, clientTime: Boolean): Map<String, String> =
        linkedMapOf(
            "User-Agent" to MID_SERVICE_USER_AGENT,
            "Content-Type" to "application/x-www-form-urlencoded",
            "Accept" to "*/*",
            "X-Mme-Device-Id" to file.deviceId,
            "X-MMe-Client-Info" to file.clientInfo,
            "X-Apple-I-MD-LU" to file.localUserUuid,
            "X-Apple-Client-App-Name" to "Setup",
            "X-Apple-I-SRL-NO" to file.serial,
            GSA_CONNECTION,
        ).apply { if (clientTime) put("X-Apple-I-Client-Time", isoTime()) }

    private fun isoTime(): String {
        val s = kotlin.time.Instant.fromEpochMilliseconds(clockMillis()).toString()
        val dot = s.indexOf('.')
        return if (dot >= 0) s.substring(0, dot) + "Z" else s
    }

    private fun Map<String, String>.require(name: String): String =
        this[name] ?: throw AnisetteException("GSA lookup has no $name")

    companion object {
        const val LOOKUP_URL = "https://gsa.apple.com/grandslam/GsService2/lookup"
        const val CLIENT_INFO = "<MacBookPro13,2> <macOS;13.1;22C65> <com.apple.AuthKit/1 (com.apple.akd/1.0)>"

        /** CFNetwork and Darwin of macOS 13.1 22C65, the release [CLIENT_INFO] names. */
        const val CFNETWORK_DARWIN = "CFNetwork/1404.0.5 Darwin/22.2.0"
        private const val MID_SERVICE_USER_AGENT = "akd/1.0 CFNetwork/1404.0.5 Darwin/22.3.0"
        private const val LOCALE = "en_US"
        private const val SERIAL_ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** CoreADI for one machine; see the Rust `adi-core` crate. Not thread safe. */
interface CoreAdi {
    fun isProvisioned(): Boolean
    fun headers(): AdiHeaders
    fun provisioningBegin(spim: ByteArray): AdiProvisioning
    fun provisioningFinish(session: Int, ptm: ByteArray, tk: ByteArray, routing: Long)
    fun sync(sim: ByteArray): AdiSync

    /** CoreADI's store, to persist after every call that can change it. */
    fun store(): ByteArray
}

class AdiHeaders(val mid: ByteArray, val otp: ByteArray, val routing: Long)
class AdiProvisioning(val cpim: ByteArray, val session: Int)
class AdiSync(val mid: ByteArray, val srm: ByteArray)

/** Where the machine file lives. It must survive restarts: it is the Mac's identity. */
interface MachineStorage {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

@Serializable
@OptIn(ExperimentalEncodingApi::class)
internal data class MachineFile(
    val version: Int,
    val hostUuid: String,
    val deviceId: String,
    val localUserUuid: String,
    val serial: String,
    val clientInfo: String,
    val routing: Long? = null,
    val provisionedAtMs: Long? = null,
    val storeB64: String = "",
) {
    fun identity() = MachineIdentity(deviceId, localUserUuid, serial, clientInfo)
    fun hostUuidBytes(): ByteArray = hostUuid.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun storeBytes(): ByteArray = if (storeB64.isEmpty()) ByteArray(0) else Base64.decode(storeB64)

    companion object {
        const val VERSION = 1
    }
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
