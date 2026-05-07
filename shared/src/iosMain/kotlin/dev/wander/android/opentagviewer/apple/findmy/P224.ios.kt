package io.github.tieo.taghistory.apple.findmy

private const val MSG = "iOS P-224 provider not wired up yet (deferred to Phase 15)"

actual object P224 {
    actual fun derivePublicX(privateKey: ByteArray): ByteArray = throw NotImplementedError(MSG)
    actual fun dhExchangeX(privateKey: ByteArray, otherPubEncoded: ByteArray): ByteArray =
        throw NotImplementedError(MSG)
}
