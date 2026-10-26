package net.primal.data.local.dao.messages

import androidx.paging.PagingSource
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter
import net.primal.domain.messages.ConversationRelation

@Dao
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
interface MessageConversationDao {

    // Conversations with a user the owner muted are left out of the list and out of the unread
    // count. Muting used to touch feeds and threads only, so a muted person could still message
    // the one who muted them and keep a badge lit on the Messages tile.
    @Query(
        """
        SELECT COALESCE(SUM(c.unreadMessagesCount), 0) FROM MessageConversationData c
        WHERE c.ownerId = :ownerId
          AND NOT EXISTS (
              SELECT 1 FROM MutedItemData m
              WHERE m.ownerId = c.ownerId AND m.type = 'User' AND m.item = c.participantId
          )
        """,
    )
    fun observeUnreadMessagesCount(ownerId: String): kotlinx.coroutines.flow.Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(data: List<MessageConversationData>)

    @Transaction
    @Query(
        """
           SELECT c.* FROM MessageConversationData c
           WHERE c.ownerId = :ownerId AND c.relation = :relation
             AND NOT EXISTS (
                 SELECT 1 FROM MutedItemData m
                 WHERE m.ownerId = c.ownerId AND m.type = 'User' AND m.item = c.participantId
             )
           ORDER BY c.lastMessageAt DESC
       """,
    )
    fun newestConversationsPagedByOwnerId(
        relation: ConversationRelation,
        ownerId: String,
    ): PagingSource<Int, MessageConversation>

    /**
     * Moves one conversation between the two tabs.
     *
     * Separate from the row upsert because the two answer different questions. The last message
     * only ever moves forward in time, while which tab a conversation belongs in can change in
     * either direction — you answer a stranger, or you unfollow someone — and it has to be
     * recomputed for rows that have no newer message to carry it.
     */
    @Query(
        """
        UPDATE MessageConversationData SET relation = :relation
        WHERE ownerId = :ownerId AND participantId = :participantId AND relation != :relation
        """,
    )
    suspend fun updateRelation(
        ownerId: String,
        participantId: String,
        relation: ConversationRelation,
    )

    @Query(
        """
        UPDATE MessageConversationData SET unreadMessagesCount = 0
        WHERE participantId = :participantId AND ownerId = :ownerId
    """,
    )
    suspend fun markConversationAsRead(participantId: String, ownerId: String)

    @Query("UPDATE MessageConversationData SET unreadMessagesCount = 0 WHERE ownerId = :ownerId")
    suspend fun markAllConversationAsRead(ownerId: String)

    @Query("SELECT * FROM MessageConversationData WHERE ownerId = :ownerId")
    suspend fun findAllByOwnerId(ownerId: String): List<MessageConversationData>

    @Query("DELETE FROM MessageConversationData WHERE ownerId = :ownerId")
    suspend fun deleteAllByOwnerId(ownerId: String)
}
