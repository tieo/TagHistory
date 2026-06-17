package io.github.tieo.taghistory.apple.anisette

import io.github.tieo.taghistory.anisette.AnisetteProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards against the anisette "thundering herd": a map refresh fans out
 * one fetch per beacon in parallel, and every fetch needs anisette
 * headers. The on-device provider is Apple ADI under Unicorn ARM
 * emulation, allocating ~240MB per call. If N concurrent callers all
 * miss the cache at once they each spin a provider call, blowing the
 * heap and freezing the UI. Only ONE provider call may serve a burst.
 */
class AnisetteClientConcurrencyTest {

    private class CountingProvider : AnisetteProvider {
        val calls = AtomicInteger(0)
        override suspend fun getHeaders(): Map<String, String> {
            calls.incrementAndGet()
            // Suspend so concurrent callers pile up at the provider gate
            // before the first one finishes and populates the cache.
            delay(50)
            return mapOf(
                "X-Apple-I-MD" to "md",
                "X-Apple-I-MD-M" to "mdm",
            )
        }

        override suspend fun version(): String = "test"
    }

    @Test
    fun concurrentBurstHitsProviderOnce() = runTest {
        val provider = CountingProvider()
        val client = AnisetteClient(provider, clockMillis = { 0L })

        val results = (1..17).map {
            async { client.getHeaders("uid", "dev", "0") }
        }.awaitAll()

        assertEquals(
            1,
            provider.calls.get(),
            "17 concurrent header requests must collapse to a single anisette provider call",
        )
        // Every caller still gets the real header values.
        results.forEach { headers ->
            assertEquals("md", headers["X-Apple-I-MD"])
            assertEquals("mdm", headers["X-Apple-I-MD-M"])
        }
    }
}
