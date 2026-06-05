package io.github.tieo.taghistory.apple.findmy

private fun NI(): Nothing = throw NotImplementedError("P-224 not available on wasmJs")

actual object P224 {
    actual fun derivePublicX(privateKey: ByteArray): ByteArray = NI()
    actual fun dhExchangeX(privateKey: ByteArray, otherPubEncoded: ByteArray): ByteArray = NI()
}
