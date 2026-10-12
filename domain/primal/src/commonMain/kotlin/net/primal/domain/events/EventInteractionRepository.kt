package net.primal.domain.events

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.SignatureException
import net.primal.domain.nostr.publisher.NostrPublishException
import net.primal.domain.publisher.PrimalPublishResult

interface EventInteractionRepository {

    companion object {
        /** NIP-25: `+` is a like. Any other content is the reaction itself, normally one emoji. */
        const val DEFAULT_REACTION = "+"

        private const val DEFAULT_DELETION_CONTENT =
            "This is a deletion request created in the LibreNostr Android application"
    }

    @Throws(
        SignatureException::class,
        NostrPublishException::class,
        CancellationException::class,
    )
    suspend fun likeEvent(
        userId: String,
        eventId: String,
        eventAuthorId: String,
        optionalTags: List<JsonArray> = emptyList(),
        reaction: String = DEFAULT_REACTION,
    ): PrimalPublishResult

    /**
     * Takes back the reactions [userId] left on [eventId]: a NIP-09 deletion for each reaction event
     * the relays have of theirs on it. A reaction cannot be edited, so changing the emoji is this
     * followed by [likeEvent].
     *
     * @throws NostrPublishException when no such reaction can be found, or the deletion is not published.
     */
    @Throws(
        SignatureException::class,
        NostrPublishException::class,
        CancellationException::class,
    )
    suspend fun unlikeEvent(
        userId: String,
        eventId: String,
        eventAuthorId: String,
    )

    @Throws(
        SignatureException::class,
        NostrPublishException::class,
        CancellationException::class,
    )
    suspend fun repostEvent(
        userId: String,
        eventId: String,
        eventKind: Int,
        eventAuthorId: String,
        eventRawNostrEvent: String,
        optionalTags: List<JsonArray> = emptyList(),
    ): PrimalPublishResult

    @Throws(
        SignatureException::class,
        NostrPublishException::class,
        CancellationException::class,
    )
    suspend fun deleteEvent(
        userId: String,
        eventIdentifier: String,
        eventKind: NostrEventKind,
        content: String = DEFAULT_DELETION_CONTENT,
        relayHint: String? = null,
    ): PrimalPublishResult

    suspend fun deleteZaps(eventId: String)
}
