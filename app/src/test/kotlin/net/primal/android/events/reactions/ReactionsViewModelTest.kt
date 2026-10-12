package net.primal.android.events.reactions

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.primal.android.navigation.EVENT_ID
import net.primal.android.navigation.INITIAL_REACTION_TYPE
import net.primal.core.testing.CoroutinesTestRule
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.events.EventRepository
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.ReactionType
import net.primal.domain.posts.FeedPost
import net.primal.domain.posts.FeedPostAuthor
import net.primal.domain.posts.FeedRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins that likes and reposts each carry their own loading flag.
 *
 * Before this, both fetches shared one `loading` boolean and ran concurrently: whichever finished
 * first — reposts, almost always, being the shorter list — flipped it to false for both tabs. The
 * likes tab then rendered its still-empty list as "no likes yet" for however long the slower
 * likes fetch was still in flight, before flipping back to the real result. Tapping "who liked
 * this" is exactly the case that surfaced it.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class ReactionsViewModelTest {

    @get:Rule
    val coroutineTestRule = CoroutinesTestRule()

    private fun savedStateHandle() = SavedStateHandle(
        mapOf(
            EVENT_ID to "a".repeat(64),
            INITIAL_REACTION_TYPE to ReactionType.LIKES.name,
        ),
    )

    @Test
    fun fetch_fastRepostsDoesNotClearLoadingForSlowerLikes() =
        runTest {
            val likesGate = CompletableDeferred<Unit>()
            val eventRepository = mockk<EventRepository> {
                every { pagedEventZaps(userId = any(), eventId = any(), articleATag = any(), zapKind = any()) } returns
                    emptyFlow()
                coEvery { fetchEventActions(eventId = any(), kind = NostrEventKind.ShortTextNoteRepost.value) } returns
                    emptyList()
                coEvery { fetchEventActions(eventId = any(), kind = NostrEventKind.Reaction.value) } coAnswers {
                    likesGate.await()
                    emptyList()
                }
            }

            val viewModel = ReactionsViewModel(
                savedStateHandle = savedStateHandle(),
                activeAccountStore = mockk(relaxed = true),
                dispatcherProvider = coroutineTestRule.dispatcherProvider,
                eventRepository = eventRepository,
                feedRepository = mockk(relaxed = true),
            )
            runCurrent()

            // Reposts already resolved; likes is still stuck behind the gate.
            viewModel.state.value.repostsLoading shouldBe false
            viewModel.state.value.likesLoading shouldBe true

            likesGate.complete(Unit)
            runCurrent()

            viewModel.state.value.likesLoading shouldBe false
        }

    @Test
    fun quotes_areLoadedAsNotesAndTheirLoadingFlagClears() =
        runTest {
            val quote = FeedPost(
                eventId = "q".repeat(64),
                author = FeedPostAuthor(authorId = "b".repeat(64), handle = "bob", displayName = "Bob"),
                kind = NostrEventKind.ShortTextNote.value,
                content = "worth reading nostr:note1…",
                tags = emptyList(),
                timestamp = Instant.fromEpochSeconds(10),
                rawNostrEvent = "{}",
            )
            val feedRepository = mockk<FeedRepository> {
                coEvery { fetchQuotes(userId = any(), noteId = "a".repeat(64)) } returns listOf(quote)
            }

            val viewModel = ReactionsViewModel(
                savedStateHandle = savedStateHandle(),
                activeAccountStore = mockk(relaxed = true),
                dispatcherProvider = coroutineTestRule.dispatcherProvider,
                eventRepository = quietEventRepository(),
                feedRepository = feedRepository,
            )
            runCurrent()

            viewModel.state.value.quotes.map { it.postId } shouldBe listOf("q".repeat(64))
            viewModel.state.value.quotesLoading shouldBe false
        }

    @Test
    fun quotes_aFailedFetchEndsLoadingWithNothingToShow() =
        runTest {
            val feedRepository = mockk<FeedRepository> {
                coEvery { fetchQuotes(userId = any(), noteId = any()) } throws NetworkException("offline")
            }

            val viewModel = ReactionsViewModel(
                savedStateHandle = savedStateHandle(),
                activeAccountStore = mockk(relaxed = true),
                dispatcherProvider = coroutineTestRule.dispatcherProvider,
                eventRepository = quietEventRepository(),
                feedRepository = feedRepository,
            )
            runCurrent()

            viewModel.state.value.quotes shouldBe emptyList()
            viewModel.state.value.quotesLoading shouldBe false
        }

    private fun quietEventRepository() = mockk<EventRepository> {
        every { pagedEventZaps(userId = any(), eventId = any(), articleATag = any(), zapKind = any()) } returns
            emptyFlow()
        coEvery { fetchEventActions(eventId = any(), kind = any()) } returns emptyList()
    }
}
