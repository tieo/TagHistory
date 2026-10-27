package io.github.tieo.taghistory.anisette

import java.io.File

/**
 * JNI bindings of the Rust ottjni crate, which runs Apple's macOS CoreADI.
 * App code goes through [JniCoreAdi] and [MacAnisetteProvider].
 *
 * Package, class and method names MUST match the Rust
 * `Java_io_github_tieo_taghistory_anisette_CoreAdiJni_*` symbols; a rename
 * breaks the binding only at call time. Every native failure throws
 * [AnisetteException].
 */
internal class CoreAdiJni private constructor() {
    companion object {
        /**
         * Absolute path of a host-built `libottjni.so`. A plain JVM has no APK
         * native-library directory, so the server names the file explicitly;
         * Android leaves it unset and resolves the library from the APK.
         */
        const val LIBRARY_PATH_PROPERTY = "taghistory.ottjni.path"

        init {
            val path = System.getProperty(LIBRARY_PATH_PROPERTY)
            if (path != null) System.load(path) else System.loadLibrary("ottjni")
        }

        @JvmStatic external fun nativeVersion(): String

        @JvmStatic external fun nativeOpen(image: ByteArray, hostUuid: ByteArray, store: ByteArray): Long

        @JvmStatic external fun nativeClose(handle: Long)

        @JvmStatic external fun nativeStore(handle: Long): ByteArray

        @JvmStatic external fun nativeIsProvisioned(handle: Long): Boolean

        /** `[mid, otp, routing as 8 big-endian bytes]` */
        @JvmStatic external fun nativeHeaders(handle: Long): Array<ByteArray>

        /** `[cpim, session as 4 big-endian bytes]` */
        @JvmStatic external fun nativeProvisioningBegin(handle: Long, spim: ByteArray): Array<ByteArray>

        @JvmStatic external fun nativeProvisioningFinish(handle: Long, session: Int, ptm: ByteArray, tk: ByteArray, routing: Long)

        /** `[mid, srm]` */
        @JvmStatic external fun nativeSync(handle: Long, sim: ByteArray): Array<ByteArray>
    }
}

/** [CoreAdi] backed by ottjni. The handle lives as long as the process. */
class JniCoreAdi(image: ByteArray, hostUuid: ByteArray, store: ByteArray) : CoreAdi {
    private val handle = CoreAdiJni.nativeOpen(image, hostUuid, store)

    override fun isProvisioned(): Boolean = CoreAdiJni.nativeIsProvisioned(handle)

    override fun headers(): AdiHeaders {
        val (mid, otp, routing) = CoreAdiJni.nativeHeaders(handle)
        return AdiHeaders(mid, otp, routing.toLong())
    }

    override fun provisioningBegin(spim: ByteArray): AdiProvisioning {
        val (cpim, session) = CoreAdiJni.nativeProvisioningBegin(handle, spim)
        return AdiProvisioning(cpim, session.toLong().toInt())
    }

    override fun provisioningFinish(session: Int, ptm: ByteArray, tk: ByteArray, routing: Long) =
        CoreAdiJni.nativeProvisioningFinish(handle, session, ptm, tk, routing)

    override fun sync(sim: ByteArray): AdiSync {
        val (mid, srm) = CoreAdiJni.nativeSync(handle, sim)
        return AdiSync(mid, srm)
    }

    override fun store(): ByteArray = CoreAdiJni.nativeStore(handle)

    companion object {
        fun version(): String = CoreAdiJni.nativeVersion()
    }
}

/** [MachineStorage] in one file, replaced atomically so a crash never leaves half a machine. */
class FileMachineStorage(private val file: File) : MachineStorage {
    override fun read(): ByteArray? = file.takeIf { it.isFile }?.readBytes()

    override fun write(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw AnisetteException("Could not write $file")
        }
    }
}

/** Big-endian unsigned value of up to 8 bytes. */
private fun ByteArray.toLong(): Long = fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
