// The wasm target does P-224 key derivation in pure Kotlin (bignum), which
// takes seconds per test in headless Chrome. Mocha's 2 s default times those
// tests out before they can report a result.
config.set({
    client: {
        mocha: {
            timeout: 60000,
        },
    },
});
