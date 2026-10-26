package net.primal.data.local.dao.notes

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface FeedPostDataCrossRefDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun connect(data: List<FeedPostDataCrossRef>)

    @Query(
        """
            SELECT * FROM FeedPostDataCrossRef 
            WHERE feedSpec = :spec AND ownerId = :ownerId 
            ORDER BY position DESC LIMIT 1
        """,
    )
    suspend fun findLastBySpec(ownerId: String, spec: String): FeedPostDataCrossRef?

    @Query(
        """
            SELECT * FROM FeedPostDataCrossRef 
            WHERE feedSpec = :spec AND ownerId = :ownerId 
            ORDER BY position ASC LIMIT 1
        """,
    )
    suspend fun findFirstBySpec(ownerId: String, spec: String): FeedPostDataCrossRef?

    @Query("DELETE FROM FeedPostDataCrossRef WHERE feedSpec = :feedSpec AND ownerId = :ownerId")
    suspend fun deleteConnectionsByDirective(ownerId: String, feedSpec: String)

    @Query("DELETE FROM FeedPostDataCrossRef WHERE ownerId = :ownerId")
    suspend fun deleteConnections(ownerId: String)

    /**
     * Takes the notes written, and the reposts made, by [authorIds] out of the "latest" feeds of
     * [ownerId] (the following feed, with and without replies).
     *
     * A feed row is a stored membership, not a live filter on who the user follows, so unfollowing
     * somebody did not remove what was already in the home feed: their notes stayed there until the
     * next full refresh. The "latest" ids are matched with `LIKE` because the spec is stored as the
     * JSON string the feed was opened with, whose whitespace and key order are not fixed.
     */
    @Query(
        """
        DELETE FROM FeedPostDataCrossRef
        WHERE ownerId = :ownerId
          AND feedSpec LIKE '%"id":"latest"%' AND feedSpec LIKE '%"kind":"notes"%'
          AND (
              eventId IN (SELECT postId FROM PostData WHERE authorId IN (:authorIds))
              OR eventId IN (SELECT repostId FROM RepostData WHERE authorId IN (:authorIds))
          )
        """,
    )
    suspend fun deleteLatestFeedRowsOfAuthors(ownerId: String, authorIds: List<String>)

    @Query(
        """
        SELECT DISTINCT feedSpec FROM FeedPostDataCrossRef
        WHERE ownerId = :ownerId AND feedSpec LIKE '%"id":"latest"%' AND feedSpec LIKE '%"kind":"notes"%'
        """,
    )
    suspend fun findLatestFeedSpecs(ownerId: String): List<String>

    @Query("DELETE FROM FeedPostDataCrossRef WHERE eventId = :eventId")
    suspend fun deletePostConnections(eventId: String)

    @Query("SELECT MIN(position) FROM FeedPostDataCrossRef")
    suspend fun findMinPosition(): Long?
}
