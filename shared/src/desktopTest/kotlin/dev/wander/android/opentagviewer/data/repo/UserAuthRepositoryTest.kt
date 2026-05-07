package io.github.tieo.taghistory.data.repo

import com.russhwolf.settings.PropertiesSettings
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import java.util.Properties
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalEncodingApi::class)
class UserAuthRepositoryTest {

    private lateinit var props: Properties
    private lateinit var repo: UserAuthRepository

    @BeforeTest
    fun setUp() {
        props = Properties()
        // Desktop SecureBlobStore is a passthrough, so the stored envelope
        // is the plaintext — fine for verifying the repo's storage seam
        // without an Android Keystore.
        repo = UserAuthRepository(
            settings = PropertiesSettings(props),
            crypto = SecureBlobStore(),
            keystoreAlias = "test-alias",
        )
    }

    @Test
    fun `empty store returns null`() {
        assertNull(repo.getUserAuth())
    }

    @Test
    fun `round-trips plaintext with UI header parsed from JSON`() {
        val body = """
            {
              "account": {
                "info": {
                  "account_name": "me@example.com",
                  "first_name": "Mari",
                  "last_name": "Bo"
                }
              }
            }
        """.trimIndent().encodeToByteArray()

        repo.storeUserAuth(body)

        val loaded = assertNotNull(repo.getUserAuth())
        assertEquals("me@example.com", loaded.user.account?.info?.accountName)
        assertEquals("Mari", loaded.user.account?.info?.firstName)
        assertEquals("Bo", loaded.user.account?.info?.lastName)
        // Envelope round-trip: what we pulled back is the base64-decoded
        // stored blob; passthrough crypto means it should equal the input.
        assertContentEquals(body, loaded.data)
    }

    @Test
    fun `clearUser removes the key`() {
        repo.storeUserAuth("""{"account":null}""".encodeToByteArray())
        assertNotNull(repo.getUserAuth())
        repo.clearUser()
        assertNull(repo.getUserAuth())
    }

    @Test
    fun `base64 encoding of stored envelope is valid`() {
        val body = byteArrayOf(1, 2, 3, 4, 5)
        // The on-disk payload is required to be base64 so
        // multiplatform-settings can hold it in a String slot.
        val payload = "{}".encodeToByteArray()
        repo.storeUserAuth(payload)
        val raw = props.getProperty("apple_account")
        assertNotNull(raw)
        // decode must succeed
        Base64.decode(raw)
    }
}
