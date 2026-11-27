package net.primal.data.repository.feed.search

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.findReplyTargetId

/** The people of a [SearchQuery], once their names have been turned into public keys. */
internal data class SearchPeople(
    val from: Set<String> = emptySet(),
    val notFrom: Set<String> = emptySet(),
    val to: Set<String> = emptySet(),
    val mentions: Set<String> = emptySet(),
)

/**
 * Checks a post against the query on this device. Relays only pre-select: many ignore the NIP-50
 * `search` field, and none of them applies exclusions, phrases or filters.
 */
internal class SearchMatcher(private val query: SearchQuery, private val people: SearchPeople) {

    fun matches(event: NostrEvent): Boolean {
        val content = event.content.lowercase()
        return matchesPeople(event) &&
            query.groups.all { group -> group.any { it.isIn(event, content) } } &&
            query.excluded.none { it.isIn(event, content) } &&
            query.requiredFilters.all { it.accepts(event) } &&
            query.excludedFilters.none { it.accepts(event) }
    }

    private fun matchesPeople(event: NostrEvent): Boolean {
        val tagged by lazy { event.tagValues("p").toSet() }
        return (people.from.isEmpty() || event.pubKey in people.from) &&
            event.pubKey !in people.notFrom &&
            (people.to.isEmpty() || (event.isReply() && tagged.any { it in people.to })) &&
            // "@name" finds what the person wrote as well as what was said to or about them.
            (people.mentions.isEmpty() || event.pubKey in people.mentions || tagged.any { it in people.mentions })
    }

    private fun SearchTerm.isIn(event: NostrEvent, content: String): Boolean =
        when (this) {
            is SearchTerm.Word -> content.contains(text)
            is SearchTerm.Phrase -> content.contains(text)
            is SearchTerm.Hashtag ->
                event.tagValues("t").any { it.equals(tag, ignoreCase = true) } || content.hasHashtag(tag)
        }

    /** `#nostr` is in "#nostr!" and in "#nostr rocks", not in "#nostrich". */
    private fun String.hasHashtag(tag: String): Boolean {
        var from = indexOf("#$tag")
        while (from >= 0) {
            val next = getOrNull(from + tag.length + 1)
            if (next == null || !(next.isLetterOrDigit() || next == '_')) return true
            from = indexOf("#$tag", startIndex = from + 1)
        }
        return false
    }

    private fun ContentFilter.accepts(event: NostrEvent): Boolean =
        when (this) {
            ContentFilter.Image ->
                event.kind == NostrEventKind.PictureNote.value || IMAGE_URL.containsMatchIn(event.content)

            ContentFilter.Video -> VIDEO_URL.containsMatchIn(event.content)
            ContentFilter.Audio -> AUDIO_URL.containsMatchIn(event.content)
            ContentFilter.Media -> ContentFilter.Image.accepts(event) || ContentFilter.Video.accepts(event)
            ContentFilter.Links -> LINK.containsMatchIn(event.content)
            ContentFilter.Replies -> event.isReply()
        }

    private fun NostrEvent.isReply(): Boolean = kind == NostrEventKind.Comment.value || tags.findReplyTargetId() != null

    private fun NostrEvent.tagValues(name: String): List<String> =
        tags.mapNotNull { tag ->
            if ((tag.getOrNull(0) as? JsonPrimitive)?.contentOrNull == name) {
                (tag.getOrNull(1) as? JsonPrimitive)?.contentOrNull
            } else {
                null
            }
        }

    private companion object {
        val IMAGE_URL = Regex("https?://\\S+\\.(?:jpe?g|png|gif|webp|avif)(?:\\?\\S*)?", RegexOption.IGNORE_CASE)
        val VIDEO_URL = Regex("https?://\\S+\\.(?:mp4|mov|webm|m3u8)(?:\\?\\S*)?", RegexOption.IGNORE_CASE)
        val AUDIO_URL = Regex("https?://\\S+\\.(?:mp3|ogg|wav|m4a|flac)(?:\\?\\S*)?", RegexOption.IGNORE_CASE)
        val LINK = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    }
}
