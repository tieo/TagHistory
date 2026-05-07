package io.github.tieo.taghistory.apple.crypto

actual fun secureRng(): Rng =
    throw NotImplementedError("iOS secure RNG not wired up yet")
