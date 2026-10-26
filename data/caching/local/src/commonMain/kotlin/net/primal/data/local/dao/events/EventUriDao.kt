package net.primal.data.local.dao.events

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import net.primal.domain.links.EventUriNostrType
import net.primal.domain.links.EventUriType

@Dao
interface EventUriDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllEventNostrUris(data: List<EventUriNostr>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllEventUris(data: List<EventUri>)

    @Query("SELECT * FROM EventUri WHERE eventId = :noteId AND type IN (:types) ORDER BY position")
    suspend fun loadEventUris(noteId: String, types: List<EventUriType> = EventUriType.entries): List<EventUri>

    /**
     * Every citation still marked "not found", regardless of which note cites it.
     *
     * The classification stored on a row is a one-time snapshot taken when the citing note was
     * first persisted, not a live join — so a citation stays [EventUriNostrType.Unsupported]
     * forever unless something explicitly re-derives it. This is how [reclassifyResolvedNoteCitations]
     * finds the candidates worth re-checking whenever a new batch of posts lands.
     */
    @Query("SELECT * FROM EventUriNostr WHERE type = :type")
    suspend fun findEventUrisByType(type: EventUriNostrType = EventUriNostrType.Unsupported): List<EventUriNostr>

    @Query("SELECT * FROM EventUriNostr WHERE eventId = :eventId ORDER BY position")
    suspend fun findEventNostrUrisByEventId(eventId: String): List<EventUriNostr>

    /**
     * Every `nostr:npub…` / `nostr:nprofile…` mention that was stored while the mentioned person's
     * profile was still unknown, i.e. whose stored display handle is the shortened npub fallback
     * (`npub1abcd…wxyz`) rather than a name.
     *
     * The handle is part of a JSON snapshot taken when the citing note was persisted, so it stays
     * an npub for good unless something rewrites it once the profile arrives. This is the query
     * [healUnresolvedMentionHandles] uses to find the rows worth rewriting. The `LIKE` on the JSON
     * text is what keeps the result small (only still-unresolved mentions match, never the whole
     * mention table), and a note persisted before the profile was known can sit anywhere in the
     * database, so there is no cheaper key to look it up by.
     */
    @Query(
        """
        SELECT * FROM EventUriNostr
        WHERE type = :type AND referencedUser LIKE '%"handle":"npub1%'
        """,
    )
    suspend fun findMentionsWithNpubHandle(type: EventUriNostrType = EventUriNostrType.Profile): List<EventUriNostr>
}
