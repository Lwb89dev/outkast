package net.primal.android.feeds.algorithm

import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.asIdentifierTag
import net.primal.domain.nostr.asPubkeyTag

/*
 * An "algorithm" is a list of people that a feed is built from: a NIP-51 follow set (kind 30000), which any other
 * client understands and which the relay feeds already know how to read (see `buildFollowSetFeedSpec`). Creating one
 * is publishing that event and adding the matching feed to the user's own feeds.
 */

private const val MAX_SLUG_LENGTH = 24
private const val SUFFIX_RANGE = 0x1000000
private const val SUFFIX_LENGTH = 6
private const val HEX_RADIX = 16
private const val TITLE_TAG = "title"

/**
 * The `d` tag of a new list: a readable slug of its [title] followed by a short random suffix, e.g.
 * `my-friends-3fa91c`. The suffix keeps two lists with the same name apart, since the `d` tag is what makes a
 * replaceable event the same event: publishing a second list under an existing identifier would overwrite the first.
 */
internal fun newAlgorithmIdentifier(title: String, random: Random = Random.Default): String {
    val slug = title.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString(separator = "")
        .replace(Regex("-+"), "-")
        .trim('-')
        .take(MAX_SLUG_LENGTH)
        .trim('-')
    val suffix = random.nextInt(SUFFIX_RANGE).toString(HEX_RADIX).padStart(SUFFIX_LENGTH, '0')
    return if (slug.isEmpty()) "algorithm-$suffix" else "$slug-$suffix"
}

/**
 * The follow set to publish: its identifier and title, then one `p` tag per person (each once, in the order given).
 * The people are public, like every follow set; the title is what other clients show for the list.
 */
internal fun buildAlgorithmEvent(
    userId: String,
    identifier: String,
    title: String,
    members: Collection<String>,
): NostrUnsignedEvent =
    NostrUnsignedEvent(
        pubKey = userId,
        kind = NostrEventKind.CategorizedPeopleList.value,
        content = "",
        tags = listOf(identifier.asIdentifierTag(), titleTag(title)) + members.distinct().map { it.asPubkeyTag() },
    )

private fun titleTag(title: String): JsonArray =
    buildJsonArray {
        add(JsonPrimitive(TITLE_TAG))
        add(JsonPrimitive(title))
    }
