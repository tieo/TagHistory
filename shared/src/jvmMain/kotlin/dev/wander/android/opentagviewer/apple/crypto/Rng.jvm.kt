package io.github.tieo.taghistory.apple.crypto

import java.security.SecureRandom

actual fun secureRng(): Rng {
    val sr = SecureRandom()
    return Rng { out -> sr.nextBytes(out) }
}
