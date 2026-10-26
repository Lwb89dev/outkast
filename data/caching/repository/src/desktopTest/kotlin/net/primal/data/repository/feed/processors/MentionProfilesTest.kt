package net.primal.data.repository.feed.processors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import net.primal.data.local.dao.events.EventUriNostr
import net.primal.data.local.dao.notes.PostData
import net.primal.data.local.dao.profiles.ProfileData
import net.primal.data.local.db.CachingDatabase
import net.primal.domain.links.EventUriNostrType
import net.primal.domain.links.ReferencedUser
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * A mention is stored as a snapshot of the mentioned person's handle. Two things used to leave the shortened npub in
 * that snapshot for good: resolving it against the relay response alone (a response rarely carries a profile an
 * earlier page already fetched), and never rewriting it once the name did arrive. These tests pin both fixes.
 */
class MentionProfilesTest {

    @Test
    fun `a mentioned person already stored locally is resolved even if the response does not carry them`() =
        withDatabase { database ->
            database.profiles().insertOrUpdateAll(data = listOf(profile(ALICE, displayName = "Alice")))

            val known = database.profilesForMentions(
                posts = listOf(post(uris = listOf("nostr:${ALICE.hexToNpubHrp()}"))),
                fromResponse = emptyMap(),
            )

            assertEquals("Alice", known[ALICE]?.displayName)
        }

    @Test
    fun `what the response carries wins over what was stored`() =
        withDatabase { database ->
            database.profiles().insertOrUpdateAll(data = listOf(profile(ALICE, displayName = "Old name")))
            val fresh = profile(ALICE, displayName = "New name")

            val known = database.profilesForMentions(
                posts = listOf(post(uris = listOf("nostr:${ALICE.hexToNpubHrp()}"))),
                fromResponse = mapOf(ALICE to fresh),
            )

            assertEquals("New name", known[ALICE]?.displayName)
        }

    @Test
    fun `a note with no mentions asks the database for nothing`() =
        withDatabase { database ->
            val known = database.profilesForMentions(
                posts = listOf(post(uris = listOf("https://example.com/a.jpg"))),
                fromResponse = emptyMap(),
            )

            assertEquals(emptyMap(), known)
        }

    @Test
    fun `a mention stored as an npub is rewritten once the person's name is known`() =
        withDatabase { database ->
            database.eventUris().upsertAllEventNostrUris(
                data = listOf(
                    mention(eventId = "note-1", user = ALICE, handle = "npub1abcd…wxyz"),
                    mention(eventId = "note-2", user = BOB, handle = "npub1efgh…stuv"),
                    mention(eventId = "note-3", user = CAROL, handle = "carol"),
                ),
            )

            database.healUnresolvedMentionHandles(profiles = listOf(profile(ALICE, displayName = "Alice")))

            assertEquals("@Alice", handleOf(database, "note-1"))
            assertEquals("@npub1efgh…stuv", handleOf(database, "note-2"), "Bob's name is still unknown")
            assertEquals("@carol", handleOf(database, "note-3"), "a name already resolved is left alone")
        }

    @Test
    fun `a profile with no name of its own does not replace the fallback`() =
        withDatabase { database ->
            database.eventUris().upsertAllEventNostrUris(
                data = listOf(mention(eventId = "note-1", user = ALICE, handle = "npub1abcd…wxyz")),
            )

            database.healUnresolvedMentionHandles(profiles = listOf(profile(ALICE)))

            assertEquals("@npub1abcd…wxyz", handleOf(database, "note-1"))
        }

    @Test
    fun `a mention keeps its place among the note's other references when it is rewritten`() =
        withDatabase { database ->
            database.eventUris().upsertAllEventNostrUris(
                data = listOf(
                    mention(eventId = "note-1", user = BOB, handle = "bob", uri = "nostr:first"),
                    mention(eventId = "note-1", user = ALICE, handle = "npub1abcd…wxyz", uri = "nostr:second"),
                ),
            )
            val before = database.eventUris().findEventNostrUrisByEventId("note-1").map { it.uri }

            database.healUnresolvedMentionHandles(profiles = listOf(profile(ALICE, displayName = "Alice")))

            assertEquals(before, database.eventUris().findEventNostrUrisByEventId("note-1").map { it.uri })
            assertNull(
                database.eventUris().findMentionsWithNpubHandle().firstOrNull { it.referencedUser?.userId == ALICE },
            )
        }

    // ------------------------------------------------------------------------------- harness

    private fun handleOf(database: CachingDatabase, eventId: String): String? =
        runBlocking { database.eventUris().findEventNostrUrisByEventId(eventId) }
            .firstOrNull()?.referencedUser?.displayUsername

    private fun withDatabase(block: suspend (CachingDatabase) -> Unit) =
        runBlocking {
            val name = "primal_mention_profiles_${counter++}.db"
            LocalDatabaseFactory.deleteDatabases(names = listOf(name))
            val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = name)
            try {
                block(database)
            } finally {
                database.close()
                LocalDatabaseFactory.deleteDatabases(names = listOf(name))
            }
        }

    private fun profile(ownerId: String, displayName: String? = null) =
        ProfileData(
            ownerId = ownerId,
            eventId = "metadata-$ownerId",
            createdAt = 1_700_000_000L,
            raw = "{}",
            displayName = displayName,
        )

    private fun post(uris: List<String>) =
        PostData(
            postId = "post-1",
            authorId = BOB,
            createdAt = 1_700_000_000L,
            tags = emptyList(),
            content = "hello",
            uris = uris,
            hashtags = emptyList(),
            sig = "sig",
            raw = "{}",
        )

    private fun mention(eventId: String, user: String, handle: String, uri: String = "nostr:$user") =
        EventUriNostr(
            eventId = eventId,
            uri = uri,
            type = EventUriNostrType.Profile,
            referencedUser = ReferencedUser(userId = user, handle = handle),
        )

    private companion object {
        var counter = 0
        const val ALICE = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
        const val BOB = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        const val CAROL = "1122334455667788990011223344556677889900112233445566778899001122"
    }
}
