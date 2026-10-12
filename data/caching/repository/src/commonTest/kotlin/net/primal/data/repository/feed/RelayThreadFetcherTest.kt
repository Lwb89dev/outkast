package net.primal.data.repository.feed

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.utils.hexToNoteHrp
import net.primal.domain.nostr.eventIdTagValues
import net.primal.domain.nostr.findCommentRootAddress
import net.primal.domain.nostr.findCommentRootEventId
import net.primal.domain.nostr.findRootEventId
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

class RelayThreadFetcherTest {

    private val noteKind = listOf(NostrEventKind.ShortTextNote.value)
    private val threadKinds = listOf(NostrEventKind.ShortTextNote.value, NostrEventKind.Comment.value)

    @Test
    fun fetch_includesRootAndDirectReplies() = runTest {
        val root = note("root", "alice", 10)
        val reply = replyTo("r1", "bob", 20, rootId = "root")
        val other = note("other", "carol", 30)
        val response = RelayThreadFetcher(FakeQuerier(listOf(root, reply, other)))
            .fetch(noteId = "root", kinds = noteKind, limit = 50)

        response.notes.map { it.id } shouldContainExactlyInAnyOrder listOf("root", "r1")
    }

    @Test
    fun fetch_fromReply_walksAncestorsAndLoadsRootThread() = runTest {
        val root = note("root", "alice", 10)
        val child = replyTo("child", "bob", 20, rootId = "root")
        val grand = replyTo("grand", "carol", 30, rootId = "root", replyId = "child")
        val sibling = replyTo("sib", "dave", 25, rootId = "root")
        val response = RelayThreadFetcher(FakeQuerier(listOf(root, child, grand, sibling)))
            .fetch(noteId = "grand", kinds = noteKind, limit = 50)

        response.notes.map { it.id } shouldContainExactlyInAnyOrder listOf("root", "child", "grand", "sib")
    }

    @Test
    fun fetch_dedupesDuplicateEvents() = runTest {
        val root = note("root", "alice", 10)
        val reply = replyTo("r1", "bob", 20, rootId = "root")
        val response = RelayThreadFetcher(FakeQuerier(listOf(root, reply, root, reply)))
            .fetch(noteId = "root", kinds = noteKind, limit = 50)

        response.notes.map { it.id } shouldBe listOf("root", "r1")
    }

    @Test
    fun fetch_missingAncestor_returnsKnownEvents() = runTest {
        val root = note("root", "alice", 10)
        val grand = replyTo("grand", "carol", 30, rootId = "root", replyId = "missing")
        val response = RelayThreadFetcher(FakeQuerier(listOf(root, grand)))
            .fetch(noteId = "grand", kinds = noteKind, limit = 50)

        response.notes.map { it.id } shouldContainExactlyInAnyOrder listOf("root", "grand")
    }

    @Test
    fun fetch_outOfOrderReplies_allPresent() = runTest {
        val root = note("root", "alice", 10)
        val late = replyTo("late", "bob", 40, rootId = "root")
        val early = replyTo("early", "carol", 15, rootId = "root")
        val mid = replyTo("mid", "dave", 25, rootId = "root")
        val response = RelayThreadFetcher(FakeQuerier(listOf(late, root, mid, early)))
            .fetch(noteId = "root", kinds = noteKind, limit = 50)

        response.notes.map { it.id } shouldContainExactlyInAnyOrder listOf("root", "late", "early", "mid")
    }

    @Test
    fun fetch_quotedNoteInContent_isFetchedAndReturnedAsReferencedEvent() = runTest {
        val quotedId = "b".repeat(64)
        val quoted = note(quotedId, "carol", 5)
        val root = note("root", "alice", 10).copy(content = "check this out nostr:${quotedId.hexToNoteHrp()}")
        val response = RelayThreadFetcher(FakeQuerier(listOf(root, quoted)))
            .fetch(noteId = "root", kinds = noteKind, limit = 50)

        response.referencedEvents.map { it.id } shouldBe listOf(quotedId)
    }

    @Test
    fun fetch_quoteTag_isFetchedAndReturnedAsReferencedEvent() = runTest {
        val quotedId = "c".repeat(64)
        val quoted = note(quotedId, "carol", 5)
        val root = note("root", "alice", 10).copy(tags = listOf(qTag(quotedId)))
        val response = RelayThreadFetcher(FakeQuerier(listOf(root, quoted)))
            .fetch(noteId = "root", kinds = noteKind, limit = 50)

        response.referencedEvents.map { it.id } shouldBe listOf(quotedId)
    }

    @Test
    fun fetch_quotedNoteNeverArrives_stillReturnsWhatItHas() = runTest {
        val missingId = "d".repeat(64)
        val root = note("root", "alice", 10).copy(content = "nostr:${missingId.hexToNoteHrp()}")
        val response = RelayThreadFetcher(FakeQuerier(listOf(root)))
            .fetch(noteId = "root", kinds = noteKind, limit = 50)

        response.notes.map { it.id } shouldBe listOf("root")
        response.referencedEvents shouldBe emptyList()
    }

    @Test
    fun fetchEvents_neverQueriesMetadata_onlyFetchMetadataForDoes() = runTest {
        val root = note("root", "alice", 10)
        val reply = replyTo("r1", "bob", 20, rootId = "root")
        val querier = RecordingQuerier(listOf(root, reply))
        val fetcher = RelayThreadFetcher(querier)

        val events = fetcher.fetchEvents(noteId = "root", kinds = noteKind, limit = 50)
        events.all.map { it.id } shouldContainExactlyInAnyOrder listOf("root", "r1")
        querier.sentFilters.none { it.kinds?.contains(NostrEventKind.Metadata.value) == true } shouldBe true

        fetcher.fetchMetadataFor(events)
        querier.sentFilters.any { it.kinds?.contains(NostrEventKind.Metadata.value) == true } shouldBe true
    }

    @Test
    fun fetch_openedNip22Comment_loadsTheWholeCommentTreeAndItsRoot() = runTest {
        val root = note("root", "alice", 10)
        val first = comment("c1", "bob", 20, root = "root", parent = "root")
        val nested = comment("c2", "carol", 30, root = "root", parent = "c1")
        val deeper = comment("c3", "dave", 40, root = "root", parent = "c2")
        val sibling = comment("c4", "erin", 25, root = "root", parent = "root")
        val unrelated = comment("x1", "frank", 50, root = "other", parent = "other")
        val response = RelayThreadFetcher(FakeQuerier(listOf(root, first, nested, deeper, sibling, unrelated)))
            .fetch(noteId = "c2", kinds = threadKinds, limit = 50)

        // All of this root's comments at every depth, and the root; nothing from another conversation.
        response.notes.map { it.id } shouldContainExactlyInAnyOrder listOf("root", "c1", "c2", "c3", "c4")
    }

    @Test
    fun fetch_openedRoot_findsCommentsThatOnlyCarryTheRootInUppercaseE() = runTest {
        val root = note("root", "alice", 10)
        // Deep enough that the comment does not name the opened note as its parent at all.
        val deep = comment("deep", "bob", 20, root = "root", parent = "somewhere-else")
        val response = RelayThreadFetcher(FakeQuerier(listOf(root, deep)))
            .fetch(noteId = "root", kinds = threadKinds, limit = 50)

        response.notes.map { it.id } shouldContainExactlyInAnyOrder listOf("root", "deep")
    }

    @Test
    fun fetch_commentsAreOnlyAskedForWhenTheCallerWantsThem() = runTest {
        val root = note("root", "alice", 10)
        val querier = RecordingQuerier(listOf(root, comment("c1", "bob", 20, root = "root", parent = "root")))

        RelayThreadFetcher(querier).fetch(noteId = "root", kinds = noteKind, limit = 50)

        querier.sentFilters.none { it.rootEventTags != null || it.rootAddressTags != null } shouldBe true
    }

    @Test
    fun fetch_openedArticleComment_asksByTheArticleAddressToo() = runTest {
        val address = "30023:alice:article"
        val onArticle = comment("c1", "bob", 20, root = null, parent = null, rootAddress = address)
        val nested = comment("c2", "carol", 30, root = null, parent = "c1", rootAddress = address)
        val querier = RecordingQuerier(listOf(onArticle, nested))

        val response = RelayThreadFetcher(querier).fetch(noteId = "c2", kinds = threadKinds, limit = 50)

        response.notes.map { it.id } shouldContainExactlyInAnyOrder listOf("c1", "c2")
        querier.sentFilters.any { it.rootAddressTags == listOf(address) } shouldBe true
    }

    @Test
    fun findRootEventId_prefersRootMarker() {
        val tags = listOf(
            eTag("mention", marker = "mention"),
            eTag("root-id", marker = "root"),
            eTag("reply-id", marker = "reply"),
        )
        tags.findRootEventId() shouldBe "root-id"
        emptyList<kotlinx.serialization.json.JsonArray>().findRootEventId() shouldBe null
    }

    @Test
    fun toThreadFeedResponse_keepsNip22CommentsAmongTheNotes() {
        val comment = comment("c1", "bob", 11, root = "n1", parent = "n1")
        val response = listOf(note("n1", "alice", 10), comment).toThreadFeedResponse(metadata = emptyList())

        response.notes.map { it.id } shouldBe listOf("n1", "c1")
    }

    @Test
    fun toThreadFeedResponse_bucketsKinds() {
        val note = note("n1", "alice", 10)
        val poll = note("p1", "bob", 11).copy(kind = NostrEventKind.Poll.value)
        val zap = note("z1", "carol", 12).copy(kind = NostrEventKind.Zap.value)
        val response = listOf(note, poll, zap).toThreadFeedResponse(metadata = emptyList())

        response.notes.map { it.id } shouldBe listOf("n1")
        response.polls.map { it.id } shouldBe listOf("p1")
        response.zaps.map { it.id } shouldBe listOf("z1")
    }

    private fun note(id: String, pubkey: String, createdAt: Long) = NostrEvent(
        id = id,
        pubKey = pubkey,
        createdAt = createdAt,
        kind = NostrEventKind.ShortTextNote.value,
        tags = emptyList(),
        content = "hello",
        sig = "sig",
    )

    private fun replyTo(
        id: String,
        pubkey: String,
        createdAt: Long,
        rootId: String,
        replyId: String? = null,
    ): NostrEvent {
        val tags = buildList {
            add(eTag(rootId, marker = "root"))
            if (replyId != null) add(eTag(replyId, marker = "reply"))
        }
        return note(id, pubkey, createdAt).copy(tags = tags)
    }

    /** A NIP-22 comment: uppercase tags name the root, lowercase ones the parent. */
    private fun comment(
        id: String,
        pubkey: String,
        createdAt: Long,
        root: String?,
        parent: String?,
        rootAddress: String? = null,
    ): NostrEvent {
        val tags = buildList {
            if (root != null) add(simpleTag("E", root))
            if (rootAddress != null) add(simpleTag("A", rootAddress))
            add(simpleTag("K", "1"))
            if (parent != null) add(simpleTag("e", parent))
            add(simpleTag("k", "1"))
        }
        return note(id, pubkey, createdAt).copy(kind = NostrEventKind.Comment.value, tags = tags)
    }

    private fun simpleTag(name: String, value: String) = buildJsonArray {
        add(JsonPrimitive(name))
        add(JsonPrimitive(value))
    }

    private fun eTag(eventId: String, marker: String) = buildJsonArray {
        add(JsonPrimitive("e"))
        add(JsonPrimitive(eventId))
        add(JsonPrimitive(""))
        add(JsonPrimitive(marker))
    }

    private fun qTag(eventId: String) = buildJsonArray {
        add(JsonPrimitive("q"))
        add(JsonPrimitive(eventId))
    }

    private class FakeQuerier(private val events: List<NostrEvent>) : RelayEventQuerier {
        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            val matched = events.filter { event -> matches(event, filter) }
            return filter.limit?.let { matched.take(it) } ?: matched
        }

        private fun matches(event: NostrEvent, filter: RelayFilter): Boolean {
            val ids = filter.ids
            if (ids != null && event.id !in ids) return false
            val authors = filter.authors
            if (authors != null && event.pubKey !in authors) return false
            val kinds = filter.kinds
            if (kinds != null && event.kind !in kinds) return false
            val eventTags = filter.eventTags
            if (eventTags != null && event.tags.eventIdTagValues().none { it in eventTags }) return false
            val rootEventTags = filter.rootEventTags
            if (rootEventTags != null && event.tags.findCommentRootEventId() !in rootEventTags) return false
            val rootAddressTags = filter.rootAddressTags
            if (rootAddressTags != null && event.tags.findCommentRootAddress() !in rootAddressTags) return false
            val since = filter.since
            if (since != null && event.createdAt < since) return false
            val until = filter.until
            if (until != null && event.createdAt > until) return false
            return true
        }
    }

    /** [FakeQuerier] plus a record of every filter it was asked, for assertions on *what* was sent. */
    private class RecordingQuerier(events: List<NostrEvent>) : RelayEventQuerier {
        private val delegate = FakeQuerier(events)
        val sentFilters = mutableListOf<RelayFilter>()

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            sentFilters.add(filter)
            return delegate.query(filter)
        }
    }
}
