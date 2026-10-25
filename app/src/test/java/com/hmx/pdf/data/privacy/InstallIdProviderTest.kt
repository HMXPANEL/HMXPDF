package com.hmx.pdf.data.privacy

import com.hmx.pdf.data.InMemoryStringStore
import com.hmx.pdf.data.isUuid
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallIdProviderTest {

    @Test
    fun generatesAPersistentVersion4Uuid() {
        val store = InMemoryStringStore()
        val provider = InstallIdProvider(store)
        val first = provider.getOrCreate()
        val second = provider.getOrCreate()

        assertTrue(isUuid(first))
        assertEquals(4, UUID.fromString(first).version())
        assertEquals(first, second)
        assertEquals(first, store.getString(INSTALL_ID_KEY))
    }

    @Test
    fun distinctProvidersGenerateDistinctIds() {
        val first = InstallIdProvider(InMemoryStringStore()).getOrCreate()
        val second = InstallIdProvider(InMemoryStringStore()).getOrCreate()
        assertNotEquals(first, second)
    }

    @Test
    fun validStoredIdIsReusedWithoutGenerating() {
        val stored = "11111111-1111-4111-8111-111111111111"
        val store = InMemoryStringStore(mapOf(INSTALL_ID_KEY to stored.uppercase()))
        val provider = InstallIdProvider(store) { error("must not generate") }
        assertEquals(stored, provider.getOrCreate())
    }

    @Test
    fun corruptStoredIdIsReplaced() {
        for (bad in listOf("", "not-a-uuid", "1234")) {
            val store = InMemoryStringStore(mapOf(INSTALL_ID_KEY to bad))
            val provider = InstallIdProvider(store)
            val id = provider.getOrCreate()
            assertTrue(isUuid(id))
            assertEquals(id, store.getString(INSTALL_ID_KEY))
        }
    }

    @Test
    fun peekDoesNotCreateAnId() {
        val store = InMemoryStringStore()
        val provider = InstallIdProvider(store) { error("must not generate") }
        assertNull(provider.peek())
        assertFalse(store.values.containsKey(INSTALL_ID_KEY))
    }

    @Test(expected = IllegalStateException::class)
    fun nonUuidGeneratorFailsSafely() {
        InstallIdProvider(InMemoryStringStore()) { "not-a-uuid" }.getOrCreate()
    }
}
