package net.primal.android.feeds.algorithm

import io.kotest.matchers.shouldBe
import net.primal.android.core.compose.profile.model.UserProfileItemUi
import net.primal.android.feeds.algorithm.CreateAlgorithmContract.UiState
import net.primal.domain.feeds.FEED_KIND_LIST
import net.primal.domain.feeds.FEED_KIND_USER
import net.primal.domain.feeds.FeedSpecKind
import net.primal.domain.feeds.PrimalFeed
import net.primal.domain.feeds.buildFollowSetFeedSpec
import net.primal.domain.feeds.defaultLibreNostrNoteFeeds
import org.junit.Test

class AlgorithmsStateTest {

    private val me = "aa".repeat(32)

    private fun person(id: String, name: String, nip05: String? = null) =
        UserProfileItemUi(profileId = id, displayName = name, internetIdentifier = nip05)

    private fun list(dTag: String, title: String, position: Int) =
        PrimalFeed(
            ownerId = me,
            spec = buildFollowSetFeedSpec(pubkey = me, dTag = dTag),
            specKind = FeedSpecKind.Notes,
            feedKind = FEED_KIND_LIST,
            title = title,
            description = "",
            position = position,
        )

    // --- Your algorithms ------------------------------------------------------------------------------------

    @Test
    fun `the default feeds are not algorithms`() {
        val defaults = defaultLibreNostrNoteFeeds(me)

        defaults.asAlgorithms() shouldBe emptyList()
    }

    @Test
    fun `lists are algorithms, in the order the user keeps them`() {
        val feeds = defaultLibreNostrNoteFeeds(me) + listOf(list("b", "Second", 3), list("a", "First", 2))

        feeds.asAlgorithms().map { it.title } shouldBe listOf("First", "Second")
    }

    @Test
    fun `a feed that is neither a default nor a list is left out`() {
        val search = PrimalFeed(
            ownerId = me,
            spec = "{\"id\":\"search\",\"query\":\"kind:1 nostr\"}",
            specKind = FeedSpecKind.Notes,
            feedKind = FEED_KIND_USER,
            title = "Search",
            description = "",
        )

        listOf(search, list("a", "Mine", 1)).asAlgorithms().map { it.title } shouldBe listOf("Mine")
    }

    // --- Creating one ---------------------------------------------------------------------------------------

    @Test
    fun `an algorithm needs a name and at least one person`() {
        val alice = person("p1", "Alice")

        UiState().canCreate shouldBe false
        UiState(name = "Friends").canCreate shouldBe false
        UiState(selected = mapOf("p1" to alice)).canCreate shouldBe false
        UiState(name = "  ", selected = mapOf("p1" to alice)).canCreate shouldBe false
        UiState(name = "Friends", selected = mapOf("p1" to alice)).canCreate shouldBe true
    }

    @Test
    fun `it cannot be created twice while it is being published`() {
        val saving = UiState(name = "Friends", selected = mapOf("p1" to person("p1", "Alice")), saving = true)

        saving.canCreate shouldBe false
    }

    @Test
    fun `an empty search shows every contact`() {
        val contacts = listOf(person("p1", "Alice"), person("p2", "Bob"))

        UiState(contacts = contacts).visibleContacts shouldBe contacts
    }

    @Test
    fun `the search matches the name or the internet identifier, ignoring case`() {
        val contacts = listOf(
            person("p1", "Alice", nip05 = "alice@example.com"),
            person("p2", "Bob", nip05 = "bob@nostr.example"),
            person("p3", "Carol"),
        )

        UiState(contacts = contacts, query = "ALI").visibleContacts.map { it.profileId } shouldBe listOf("p1")
        UiState(contacts = contacts, query = "nostr.ex").visibleContacts.map { it.profileId } shouldBe listOf("p2")
        UiState(contacts = contacts, query = " carol ").visibleContacts.map { it.profileId } shouldBe listOf("p3")
        UiState(contacts = contacts, query = "zzz").visibleContacts shouldBe emptyList()
    }

    @Test
    fun `people chosen from the search stay listed once the search is gone, contacts are not repeated`() {
        val alice = person("p1", "Alice")
        val stranger = person("p9", "Stranger")
        val state = UiState(contacts = listOf(alice), selected = mapOf("p1" to alice, "p9" to stranger))

        state.selectedOutsideContacts shouldBe listOf(stranger)
    }
}
