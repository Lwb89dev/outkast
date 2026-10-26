package net.primal.data.repository.mute

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.mutes.ListType
import net.primal.data.local.dao.mutes.MutedItemData
import net.primal.data.local.dao.mutes.MutedItemType
import net.primal.data.local.db.CachingDatabase
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.asPubkeyTag
import net.primal.domain.nostr.publisher.NostrPublishException
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.publisher.PrimalPublishResult
import net.primal.domain.publisher.PrimalPublisher
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * The mute list as the app really handles it: read from the relays, kept in the local database that the feeds filter
 * on, and republished when it changes. A relay that answers with nothing must never be taken for "the list is empty",
 * and a mute must show up locally at once instead of after a relay round trip, and be taken back if it cannot be
 * published.
 */
class MutedItemRepositoryRelayTest {

    @Test
    fun `relays that return no mute list do not wipe the local one`() =
        withRepository(relayList = null) { database, repository, _ ->
            seedLocal(database, MUTED_A, MUTED_B)

            repository.fetchAndPersistMuteList(userId = USER_ID)

            assertEquals(setOf(MUTED_A, MUTED_B), localMuted(database))
        }

    @Test
    fun `a mute list the relays do return replaces the local one, an emptied one included`() =
        withRepository(relayList = muteListEvent(MUTED_B)) { database, repository, querier ->
            seedLocal(database, MUTED_A)

            repository.fetchAndPersistMuteList(userId = USER_ID)
            assertEquals(setOf(MUTED_B), localMuted(database))

            querier.relayList = muteListEvent()
            repository.fetchAndPersistMuteList(userId = USER_ID)
            assertEquals(emptySet(), localMuted(database))
        }

    @Test
    fun `muting a user is visible locally and publishes the merged list`() =
        withRepository(relayList = muteListEvent(MUTED_A)) { database, repository, _ ->
            seedLocal(database, MUTED_A)

            repository.muteUserAndPersistMuteList(userId = USER_ID, mutedUserId = MUTED_B)

            assertEquals(setOf(MUTED_A, MUTED_B), localMuted(database))
            assertEquals(listOf(setOf(MUTED_A, MUTED_B)), publishedPubkeys)
        }

    @Test
    fun `a mute that cannot be published is taken back`() =
        withRepository(relayList = muteListEvent(MUTED_A), publishFails = true) { database, repository, _ ->
            seedLocal(database, MUTED_A)

            assertFailsWith<NostrPublishException> {
                repository.muteUserAndPersistMuteList(userId = USER_ID, mutedUserId = MUTED_B)
            }

            assertEquals(setOf(MUTED_A), localMuted(database))
        }

    @Test
    fun `unmuting works from the local copy when the relays give nothing`() =
        withRepository(relayList = null) { database, repository, _ ->
            seedLocal(database, MUTED_A, MUTED_B)

            repository.unmuteUserAndPersistMuteList(userId = USER_ID, unmutedUserId = MUTED_A)

            assertEquals(setOf(MUTED_B), localMuted(database))
            assertEquals(listOf(setOf(MUTED_B)), publishedPubkeys)
        }

    // ------------------------------------------------------------------------------- harness

    private val publishedPubkeys = mutableListOf<Set<String>>()

    private class FakeQuerier(var relayList: NostrEvent?) : RelayEventQuerier {
        override suspend fun query(filter: RelayFilter): List<NostrEvent> =
            if (NostrEventKind.MuteList.value in filter.kinds.orEmpty()) listOfNotNull(relayList) else emptyList()
    }

    private fun withRepository(
        relayList: NostrEvent?,
        publishFails: Boolean = false,
        block: suspend (CachingDatabase, MutedItemRepositoryImpl, FakeQuerier) -> Unit,
    ) = runBlocking {
        publishedPubkeys.clear()
        val name = "primal_mute_relay_${counter++}.db"
        LocalDatabaseFactory.deleteDatabases(names = listOf(name))
        val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = name)
        val dispatcher = UnconfinedTestDispatcher()
        val querier = FakeQuerier(relayList)
        val publisher = mockk<PrimalPublisher>()
        coEvery { publisher.signPublishImportNostrEvent(any(), any()) } coAnswers {
            val event = firstArg<NostrUnsignedEvent>()
            if (publishFails) throw NostrPublishException(cause = null)
            publishedPubkeys += event.tags.map { it[1].toString().trim('"') }.toSet()
            PrimalPublishResult(
                nostrEvent = NostrEvent(
                    id = "published",
                    pubKey = USER_ID,
                    createdAt = 1L,
                    kind = event.kind,
                    tags = event.tags,
                    content = event.content,
                    sig = "sig",
                ),
            )
        }
        val repository = MutedItemRepositoryImpl(
            dispatcherProvider = mockk<DispatcherProvider> {
                every { io() } returns dispatcher
                every { main() } returns dispatcher
            },
            database = database,
            primalPublisher = publisher,
            relayEventQuerier = querier,
        )
        try {
            block(database, repository, querier)
        } finally {
            database.close()
            LocalDatabaseFactory.deleteDatabases(names = listOf(name))
        }
    }

    private suspend fun seedLocal(database: CachingDatabase, vararg pubkeys: String) {
        database.mutedItems().upsertAll(
            data = pubkeys.map {
                MutedItemData(item = it, ownerId = USER_ID, type = MutedItemType.User, listType = ListType.MuteList)
            }.toSet(),
        )
    }

    private suspend fun localMuted(database: CachingDatabase): Set<String> =
        database.mutedItems().getListByOwnerId(ownerId = USER_ID, listType = ListType.MuteList)
            .map { it.item }
            .toSet()

    private fun muteListEvent(vararg pubkeys: String) =
        NostrEvent(
            id = "mute-list",
            pubKey = USER_ID,
            createdAt = 1_700_000_000L,
            kind = NostrEventKind.MuteList.value,
            tags = pubkeys.map { it.asPubkeyTag() },
            content = "",
            sig = "sig",
        )

    private companion object {
        var counter = 0
        const val USER_ID = "user-pubkey"
        const val MUTED_A = "muted-pubkey-a"
        const val MUTED_B = "muted-pubkey-b"
    }
}
