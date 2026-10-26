package net.primal.data.local.dao.mutes

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RewriteQueriesToDropUnusedColumns
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MutedItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(data: Set<MutedItemData>)

    @Query("DELETE FROM MutedItemData WHERE ownerId = :ownerId")
    suspend fun deleteAllByOwnerId(ownerId: String)

    @Query("DELETE FROM MutedItemData WHERE ownerId = :ownerId AND listType = :listType")
    suspend fun deleteListByOwnerId(ownerId: String, listType: ListType)

    @Query("SELECT * FROM MutedItemData WHERE ownerId = :ownerId AND listType = :listType")
    suspend fun getListByOwnerId(ownerId: String, listType: ListType): List<MutedItemData>

    // LEFT JOIN: a muted user whose profile was never fetched is still muted, and has to be listed so that it
    // can be unmuted; an inner join made such a mute invisible and permanent. Only the mute row is selected (the
    // profile comes through MutedUser's @Relation): selecting both tables' columns returned two `ownerId`s, and which
    // one Room reads is not something to rely on, least of all when the profile side can now be NULL.
    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT m.* FROM MutedItemData m
        LEFT JOIN ProfileData p ON m.item = p.ownerId
        WHERE m.ownerId = :ownerId AND m.type = 'User'
        ORDER BY COALESCE(p.displayName, m.item) ASC
        """,
    )
    fun observeMutedUsersByOwnerId(ownerId: String): Flow<List<MutedUser>>

    @Query(
        """
        SELECT EXISTS(
            SELECT * FROM MutedItemData WHERE item = :pubkey AND ownerId = :ownerId AND listType = 'MuteList'
        )
        """,
    )
    fun observeIsUserMutedByOwnerId(pubkey: String, ownerId: String): Flow<Boolean>

    @Query(
        """
        SELECT EXISTS(
            SELECT * FROM MutedItemData WHERE item = :pubkey AND ownerId = :ownerId AND listType = 'StreamMuteList'
        )
        """,
    )
    fun observeIsStreamMutedByOwnerId(pubkey: String, ownerId: String): Flow<Boolean>

    @Query("SELECT * FROM MutedItemData WHERE ownerId = :ownerId AND type = :type")
    fun observeMutedItemsByType(ownerId: String, type: MutedItemType): Flow<List<MutedItemData>>

    @Query("SELECT item FROM MutedItemData WHERE ownerId = :ownerId AND type = 'User'")
    fun observeMutedProfileIdsByOwnerId(ownerId: String): Flow<List<String>>
}
