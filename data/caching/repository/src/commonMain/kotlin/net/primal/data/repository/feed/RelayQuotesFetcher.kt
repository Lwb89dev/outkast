package net.primal.data.repository.feed

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/** The notes that quote one note, with what is needed to show them. */
internal class NoteQuotes(
    /** Newest first. */
    val quotes: List<NostrEvent>,
    /** The quoted note itself, so that each quote can render it inline instead of "event not found". */
    val quotedNote: NostrEvent?,
    val metadata: List<NostrEvent>,
)

/**
 * Finds the notes that quote a note, for the list behind a note's quote counter.
 *
 * A quote names its target in two ways and the two generations of clients use different ones, so both
 * are asked: modern clients put the note in a `q` tag (NIP-18) and the others tag it as an `e` mention
 * and embed its `nostr:` link in the text, which only an `e` query can find. Whatever either returns is
 * kept only if it really quotes the note, see [isQuoteOf]: the `e` query also returns every reply.
 */
internal class RelayQuotesFetcher(private val querier: RelayEventQuerier) {

    suspend fun fetch(noteId: String, limit: Int): NoteQuotes {
        val (candidates, quotedNote) = coroutineScope {
            val byQuoteTag = async { query(RelayFilter(kinds = TEXT_NOTE, quoteTags = listOf(noteId), limit = limit)) }
            val byEventTag = async { query(RelayFilter(kinds = TEXT_NOTE, eventTags = listOf(noteId), limit = limit)) }
            val target = async { query(RelayFilter(ids = listOf(noteId), limit = 1)).firstOrNull { it.id == noteId } }
            (byQuoteTag.await() + byEventTag.await()) to target.await()
        }

        val quotes = candidates
            .filter { it.isQuoteOf(noteId) }
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
        return NoteQuotes(quotes = quotes, quotedNote = quotedNote, metadata = fetchMetadata(quotes))
    }

    private suspend fun fetchMetadata(quotes: List<NostrEvent>): List<NostrEvent> {
        val authors = quotes.map { it.pubKey }.distinct()
        if (authors.isEmpty()) return emptyList()
        val filter = RelayFilter(kinds = listOf(NostrEventKind.Metadata.value), authors = authors, limit = authors.size)
        return query(filter).latestMetadataByPubkey()
    }

    private suspend fun query(filter: RelayFilter): List<NostrEvent> =
        runCatching { querier.query(filter) }.getOrDefault(emptyList())

    private companion object {
        val TEXT_NOTE = listOf(NostrEventKind.ShortTextNote.value)
    }
}
