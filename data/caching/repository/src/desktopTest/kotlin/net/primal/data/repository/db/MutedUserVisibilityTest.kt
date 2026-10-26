package net.primal.data.repository.db

import androidx.paging.PagingSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.primal.data.local.dao.messages.MessageConversationData
import net.primal.data.local.dao.mutes.ListType
import net.primal.data.local.dao.mutes.MutedItemData
import net.primal.data.local.dao.mutes.MutedItemType
import net.primal.data.local.dao.notifications.NotificationData
import net.primal.data.local.dao.notifications.NotificationGroupCrossRef
import net.primal.data.local.db.CachingDatabase
import net.primal.domain.messages.ConversationRelation
import net.primal.domain.notifications.NotificationType
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * Muting used to reach feeds and threads only, so a muted person kept appearing in the places they are most visible
 * to the one who muted them: Notifications (every reply, mention, reaction and zap) and Messages (the conversation and
 * its unread badge). These tests pin that both leave a muted user out, and bring them back on unmute.
 */
class MutedUserVisibilityTest {

    @Test
    fun `a muted user's notifications are left out of the seen list`() =
        withDatabase { database ->
            seedNotifications(database)
            mute(database, MUTED)

            assertEquals(listOf("from-other"), seenNotificationIds(database))
        }

    @Test
    fun `a muted user's notifications are left out of the unseen list`() =
        withDatabase { database ->
            seedNotifications(database, seen = false)
            mute(database, MUTED)

            val unseen = database.notifications().unseenByGroup(OWNER, GROUP_KEY, showFollows = true).first()

            assertEquals(listOf("from-other"), unseen.map { it.data.notificationId })
        }

    @Test
    fun `unmuting brings the notifications back`() =
        withDatabase { database ->
            seedNotifications(database)
            mute(database, MUTED)
            database.mutedItems().deleteListByOwnerId(ownerId = OWNER, listType = ListType.MuteList)

            assertEquals(setOf("from-muted", "from-other"), seenNotificationIds(database).toSet())
        }

    @Test
    fun `another account's mute list does not hide anything`() =
        withDatabase { database ->
            seedNotifications(database)
            database.mutedItems().upsertAll(data = setOf(muted(owner = "someone-else", item = MUTED)))

            assertEquals(setOf("from-muted", "from-other"), seenNotificationIds(database).toSet())
        }

    @Test
    fun `a notification with no actor is still shown`() =
        withDatabase { database ->
            database.notifications().upsertAll(
                data = listOf(notification("no-actor", actor = null)),
            )
            database.notificationGroupCrossRef().insertAll(refs = listOf(groupRef("no-actor")))
            mute(database, MUTED)

            assertEquals(listOf("no-actor"), seenNotificationIds(database))
        }

    @Test
    fun `a muted user's conversation is left out of the list and out of the unread count`() =
        withDatabase { database ->
            database.messageConversations().upsertAll(
                data = listOf(
                    conversation(participant = MUTED, unread = 3),
                    conversation(participant = OTHER, unread = 2),
                ),
            )
            mute(database, MUTED)

            val page = database.messageConversations()
                .newestConversationsPagedByOwnerId(relation = ConversationRelation.Follows, ownerId = OWNER)
                .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
            val shown = (page as? PagingSource.LoadResult.Page ?: fail("load failed: $page")).data

            assertEquals(listOf(OTHER), shown.map { it.data.participantId })
            assertEquals(2, database.messageConversations().observeUnreadMessagesCount(OWNER).first())
        }

    // ------------------------------------------------------------------------------- harness

    private suspend fun seenNotificationIds(database: CachingDatabase): List<String> {
        val page = database.notifications()
            .seenByGroupPaged(ownerId = OWNER, groupKey = GROUP_KEY, showFollows = true, utcOffsetSeconds = 0)
            .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
        return (page as? PagingSource.LoadResult.Page ?: fail("load failed: $page")).data
            .map { it.data.notificationId }
    }

    private suspend fun seedNotifications(database: CachingDatabase, seen: Boolean = true) {
        database.notifications().upsertAll(
            data = listOf(
                notification("from-muted", actor = MUTED, seen = seen),
                notification("from-other", actor = OTHER, seen = seen),
            ),
        )
        database.notificationGroupCrossRef().insertAll(
            refs = listOf(groupRef("from-muted"), groupRef("from-other")),
        )
    }

    private suspend fun mute(database: CachingDatabase, user: String) {
        database.mutedItems().upsertAll(data = setOf(muted(owner = OWNER, item = user)))
    }

    private fun muted(owner: String, item: String) =
        MutedItemData(item = item, ownerId = owner, type = MutedItemType.User, listType = ListType.MuteList)

    private fun notification(id: String, actor: String?, seen: Boolean = true) =
        NotificationData(
            notificationId = id,
            ownerId = OWNER,
            createdAt = 1_700_000_000L,
            type = NotificationType.YOUR_POST_WAS_LIKED,
            seenGloballyAt = if (seen) 1_700_000_100L else null,
            actionUserId = actor,
        )

    private fun groupRef(notificationId: String) =
        NotificationGroupCrossRef(notificationId = notificationId, ownerId = OWNER, groupKey = GROUP_KEY)

    private fun conversation(participant: String, unread: Int) =
        MessageConversationData(
            ownerId = OWNER,
            participantId = participant,
            lastMessageId = "message-$participant",
            lastMessageAt = 1_700_000_000L,
            unreadMessagesCount = unread,
            relation = ConversationRelation.Follows,
            participantMetadataId = null,
        )

    private fun withDatabase(block: suspend (CachingDatabase) -> Unit) =
        runBlocking {
            val name = "primal_muted_visibility_${counter++}.db"
            LocalDatabaseFactory.deleteDatabases(names = listOf(name))
            val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = name)
            try {
                block(database)
            } finally {
                database.close()
                LocalDatabaseFactory.deleteDatabases(names = listOf(name))
            }
        }

    private companion object {
        var counter = 0
        const val OWNER = "owner-pubkey"
        const val MUTED = "muted-pubkey"
        const val OTHER = "other-pubkey"
        const val GROUP_KEY = "ALL"
    }
}
