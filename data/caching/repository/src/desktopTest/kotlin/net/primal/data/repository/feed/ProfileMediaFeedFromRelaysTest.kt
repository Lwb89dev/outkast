package net.primal.data.repository.feed

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * The media tab of a profile was the one profile tab that still asked the centralized feed API,
 * which never answers in a relay-only build, so nobody's past pictures could be seen from their
 * profile. It is now that person's own notes, kept when they carry an image or a video.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileMediaFeedFromRelaysTest {

    @Test
    fun `the media tab asks the relays for that person's plain notes, not for reposts`() =
        runTest {
            val querier = ScriptedQuerier()

            fetcher(querier).fetchMedia(limit = 20)

            assertEquals(listOf(listOf(SOMEONE)), querier.noteFilters.map { it.authors })
            assertEquals(
                listOf(listOf(NostrEventKind.ShortTextNote.value)),
                querier.noteFilters.map { it.kinds },
                "a repost is somebody else's picture, and would only use up the window",
            )
        }

    @Test
    fun `only notes that carry an image or a video are kept`() =
        runTest {
            val querier = ScriptedQuerier(
                windows = listOf(
                    listOf(
                        note(id = "text", at = 100, content = "just words"),
                        note(id = "photo", at = 99, content = "look https://example.com/a.jpg"),
                        note(id = "page", at = 98, content = "read https://example.com/article"),
                        note(id = "clip", at = 97, content = "https://example.com/v.mp4"),
                        note(id = "song", at = 96, content = "https://example.com/s.mp3"),
                    ),
                ),
            )

            val response = fetcher(querier).fetchMedia(limit = 20)

            assertEquals(listOf("photo", "clip"), response.notes.map { it.id })
        }

    @Test
    fun `a link with no file extension is a picture when the imeta tag says so`() =
        runTest {
            val blossomLink = "https://cdn.example.com/0123456789abcdef"
            val querier = ScriptedQuerier(
                windows = listOf(
                    listOf(
                        note(
                            id = "described",
                            at = 100,
                            content = blossomLink,
                            tags = listOf(imeta(blossomLink, "image/webp")),
                        ),
                        note(id = "undescribed", at = 99, content = "https://cdn.example.com/fedcba9876543210"),
                    ),
                ),
            )

            val response = fetcher(querier).fetchMedia(limit = 20)

            assertEquals(listOf("described"), response.notes.map { it.id })
        }

    @Test
    fun `replies with a picture are kept, because the grid is everything the person posted`() =
        runTest {
            val querier = ScriptedQuerier(
                windows = listOf(
                    listOf(
                        note(
                            id = "reply",
                            at = 100,
                            content = "https://example.com/r.png",
                            tags = listOf(eventTag("parent")),
                        ),
                    ),
                ),
            )

            val response = fetcher(querier).fetchMedia(limit = 20)

            assertEquals(listOf("reply"), response.notes.map { it.id })
        }

    @Test
    fun `a stretch of text-only notes does not end the grid, it is walked past`() =
        runTest {
            // Three windows of nothing but words, then a picture: the first page must go back far
            // enough to find it instead of coming back empty and being read as "no pictures".
            val querier = ScriptedQuerier(
                windows = listOf(
                    textWindow(from = 300, count = LIMIT),
                    textWindow(from = 200, count = LIMIT),
                    listOf(note(id = "old-photo", at = 150, content = "https://example.com/old.jpg")),
                ),
            )

            val response = fetcher(querier).fetchMedia(limit = LIMIT)

            assertEquals(listOf("old-photo"), response.notes.map { it.id })
        }

    @Test
    fun `when the walk gives up empty-handed it says where to resume`() =
        runTest {
            // Four full windows without a picture is the cap. The page is empty, but the history is
            // not over, so the cursor has to move on or the grid would stop for good.
            val querier = ScriptedQuerier(
                windows = (0 until 4).map { textWindow(from = 1_000 - it * 100L, count = LIMIT) },
            )

            val page = fetcher(querier).fetchPage(
                userId = ME,
                feedSpec = MEDIA_SPEC,
                includeReplies = false,
                limit = LIMIT,
            )

            assertTrue(page.page.isEmpty())
            assertEquals(false, page.unreachable)
            assertEquals(1_000 - 3 * 100L - (LIMIT - 1), page.resumeUntil, "continue from the oldest note looked at")
        }

    @Test
    fun `the media spec is told apart from the notes tab`() =
        runTest {
            val querier = ScriptedQuerier()

            fetcher(querier).fetch(
                userId = ME,
                feedSpec = """{"id":"feed","kind":"notes","notes":"authored","pubkey":"$SOMEONE"}""",
                includeReplies = false,
                limit = 20,
            )

            assertEquals(
                listOf(listOf(NostrEventKind.ShortTextNote.value, NostrEventKind.ShortTextNoteRepost.value)),
                querier.noteFilters.map { it.kinds },
                "the notes tab keeps asking for reposts too",
            )
            assertNull(querier.noteFilters.single().until)
        }

    // ------------------------------------------------------------------------------- harness

    /**
     * Answers each successive note query with the next scripted window (newest first, like a
     * relay), and everything else (follow list, metadata, referenced notes) with nothing.
     */
    private class ScriptedQuerier(private val windows: List<List<NostrEvent>> = emptyList()) : RelayEventQuerier {
        val noteFilters = mutableListOf<RelayFilter>()

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            val kinds = filter.kinds.orEmpty()
            if (!kinds.contains(NostrEventKind.ShortTextNote.value)) return emptyList()
            val window = windows.getOrNull(noteFilters.size).orEmpty()
            noteFilters += filter
            return window
        }
    }

    private fun TestScope.fetcher(querier: RelayEventQuerier): RelayNotesFeedFetcher {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return RelayNotesFeedFetcher(
            querier = querier,
            coordinator = FetchCoordinator(
                dispatcherProvider = mockk<DispatcherProvider> {
                    every { io() } returns dispatcher
                    every { main() } returns dispatcher
                },
            ),
        )
    }

    private suspend fun RelayNotesFeedFetcher.fetchMedia(limit: Int) =
        fetch(userId = ME, feedSpec = MEDIA_SPEC, includeReplies = false, limit = limit)

    private fun textWindow(from: Long, count: Int) =
        (0 until count).map { note(id = "text-$from-$it", at = from - it, content = "words $it") }

    private fun eventTag(eventId: String): JsonArray =
        buildJsonArray {
            add(JsonPrimitive("e"))
            add(JsonPrimitive(eventId))
        }

    private fun imeta(url: String, mimeType: String): JsonArray =
        buildJsonArray {
            add(JsonPrimitive("imeta"))
            add(JsonPrimitive("url $url"))
            add(JsonPrimitive("m $mimeType"))
        }

    private fun note(id: String, at: Long, content: String, tags: List<JsonArray> = emptyList()) =
        NostrEvent(
            id = id,
            pubKey = SOMEONE,
            createdAt = at,
            kind = NostrEventKind.ShortTextNote.value,
            tags = tags,
            content = content,
            sig = "sig",
        )

    private companion object {
        /** Valid hex pubkeys, because the spec helpers check that before matching. */
        const val ME = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
        const val SOMEONE = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        const val MEDIA_SPEC = """{"id":"feed","kind":"notes","notes":"user_media_thumbnails","pubkey":"$SOMEONE"}"""
        const val LIMIT = 5
    }
}
