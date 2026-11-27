package net.primal.data.repository.feed.search

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** One thing a post has to contain (or, excluded, must not). All text is lower case. */
internal sealed class SearchTerm {
    data class Word(val text: String) : SearchTerm()
    data class Phrase(val text: String) : SearchTerm()
    data class Hashtag(val tag: String) : SearchTerm()
}

internal enum class ContentFilter { Image, Video, Audio, Media, Links, Replies }

internal enum class SearchOrder { Latest, Top, Likes, Replies, Zaps }

internal data class Minimums(
    val likes: Int = 0,
    val replies: Int = 0,
    val reposts: Int = 0,
    val zaps: Int = 0,
    val interactions: Int = 0,
) {
    val any: Boolean get() = likes > 0 || replies > 0 || reposts > 0 || zaps > 0 || interactions > 0
}

/**
 * What was typed in the search box, read the way X/Twitter reads it:
 *
 * - words must all be there; `"two words"` is an exact phrase; `a OR b` accepts either;
 * - `-word` excludes; `#tag` is a hashtag; `@name` is a mention;
 * - `from:`, `to:`, `since:`, `until:`, `filter:` / `-filter:`, `min_faves:`, `min_replies:`,
 *   `min_retweets:`;
 *
 * plus the commands the advanced search screen writes (`kind:`, `scope:`, `orderby:`, ...).
 * People are kept as typed (npub, hex or name): finding who is meant needs the network.
 */
internal data class SearchQuery(
    /** Every group has to match; a group matches when any one of its terms does. */
    val groups: List<List<SearchTerm>> = emptyList(),
    val excluded: List<SearchTerm> = emptyList(),
    val from: List<String> = emptyList(),
    val notFrom: List<String> = emptyList(),
    val to: List<String> = emptyList(),
    val mentions: List<String> = emptyList(),
    val zappedBy: List<String> = emptyList(),
    val replyToEventIds: List<String> = emptyList(),
    val requiredFilters: Set<ContentFilter> = emptySet(),
    val excludedFilters: Set<ContentFilter> = emptySet(),
    val minimums: Minimums = Minimums(),
    val since: Long? = null,
    val until: Long? = null,
    val kinds: List<Int> = emptyList(),
    val scope: String = "",
    val order: SearchOrder = SearchOrder.Latest,
) {

    companion object {

        private val TOKEN = Regex("-?[A-Za-z_]+:\"[^\"]*\"|-?\"[^\"]*\"|\\(|\\)|[^\\s()\"]+")
        private val CONTROL = Regex("^(-?)([A-Za-z_]+):(.+)$")
        private val KEY_HEX = Regex("[0-9a-fA-F]{64}")
        private val ISO_DATE = Regex("(\\d{4})-(\\d{2})-(\\d{2})")

        /** Commands the advanced search screen writes that have no meaning on relays. */
        private val IGNORED_KEYS = setOf(
            "pas",
            "orientation",
            "minwords",
            "maxwords",
            "minduration",
            "maxduration",
            "minscore",
            "lang",
        )

        fun parse(text: String): SearchQuery = Parser().apply { read(text) }.build()

        private class Parser {
            private var query = SearchQuery()
            private val groups = mutableListOf<MutableList<SearchTerm>>()
            private val excluded = mutableListOf<SearchTerm>()
            private var orPending = false
            private var lastWasTerm = false

            fun read(text: String) {
                TOKEN.findAll(text).map { it.value }.forEach { token ->
                    when {
                        token == "(" || token == ")" -> Unit
                        // Upper case only, as on X: a lower-case "or" is a word like any other.
                        token == "OR" -> orPending = lastWasTerm
                        else -> readToken(token)
                    }
                }
            }

            fun build(): SearchQuery = query.copy(groups = groups, excluded = excluded)

            private fun readToken(token: String) {
                val control = CONTROL.matchEntire(token)
                if (control != null) {
                    val (minus, key, value) = control.destructured
                    if (readControl(negated = minus == "-", key = key.lowercase(), value = value.trim('"'))) {
                        lastWasTerm = false
                        orPending = false
                        return
                    }
                }
                readTerm(token)
            }

            @Suppress("ReturnCount") // Mentions and unreadable tokens leave early; the rest is one path.
            private fun readTerm(token: String) {
                val negated = token.length > 1 && token.startsWith("-")
                val body = if (negated) token.substring(1) else token

                if (!negated && body.startsWith("@") && body.length > 1) {
                    query = query.copy(mentions = query.mentions + body.removePrefix("@").trimPunctuation())
                    lastWasTerm = false
                    return
                }
                if (!negated && (body.startsWith("npub1") || body.startsWith("nprofile1"))) {
                    query = query.copy(mentions = query.mentions + body)
                    lastWasTerm = false
                    return
                }

                val term = body.toTerm() ?: return
                if (negated) {
                    excluded += term
                    lastWasTerm = false
                } else {
                    if (orPending && groups.isNotEmpty()) {
                        groups.last() += term
                    } else {
                        groups += mutableListOf(term)
                    }
                    lastWasTerm = true
                }
                orPending = false
            }

            private fun String.toTerm(): SearchTerm? =
                when {
                    startsWith("\"") ->
                        trim('"').trim().lowercase().takeIf { it.isNotEmpty() }?.let { SearchTerm.Phrase(it) }

                    startsWith("#") ->
                        removePrefix("#").trimPunctuation().lowercase()
                            .takeIf { it.isNotEmpty() }?.let { SearchTerm.Hashtag(it) }

                    else -> lowercase().takeIf { it.isNotEmpty() }?.let { SearchTerm.Word(it) }
                }

            private fun String.trimPunctuation() = trimEnd('.', ',', ';', ':', '!', '?')

            /** @return false when [key] is not a command, so the token is searched for as text. */
            // One branch per operator: the complexity is the number of operators.
            @Suppress("CyclomaticComplexMethod", "ReturnCount")
            private fun readControl(
                negated: Boolean,
                key: String,
                value: String,
            ): Boolean {
                val person = value.removePrefix("@")
                query = when (key) {
                    "from" ->
                        if (negated) {
                            query.copy(notFrom = query.notFrom + person)
                        } else {
                            query.copy(from = query.from + person)
                        }

                    "to" ->
                        // A bare 64-digit id after `to:` has always meant "replies to that note".
                        if (KEY_HEX.matches(person)) {
                            query.copy(replyToEventIds = query.replyToEventIds + person.lowercase())
                        } else {
                            query.copy(to = query.to + person)
                        }

                    "zappedby" -> query.copy(zappedBy = query.zappedBy + person)
                    "since" -> query.copy(since = value.toTimestamp() ?: query.since)
                    "until" -> query.copy(until = value.toTimestamp() ?: query.until)
                    "kind" -> query.copy(kinds = query.kinds + listOfNotNull(value.toIntOrNull()))
                    "scope" -> query.copy(scope = value.lowercase())
                    "orderby" -> query.copy(order = value.toOrder())
                    "filter" -> readFilter(negated, value.lowercase()) ?: return false
                    "repliestokind" -> query.copy(requiredFilters = query.requiredFilters + ContentFilter.Replies)
                    "min_faves", "minlikes" -> query.withMinimum { copy(likes = value.toCount()) }
                    "min_replies", "minreplies" -> query.withMinimum { copy(replies = value.toCount()) }
                    "min_retweets", "min_reposts", "minreposts" ->
                        query.withMinimum { copy(reposts = value.toCount()) }
                    "minzaps" -> query.withMinimum { copy(zaps = value.toCount()) }
                    "mininteractions" -> query.withMinimum { copy(interactions = value.toCount()) }
                    in IGNORED_KEYS -> query
                    else -> return false
                }
                return true
            }

            private fun readFilter(negated: Boolean, value: String): SearchQuery? {
                val filter = when (value) {
                    "image", "images" -> ContentFilter.Image
                    "video", "videos", "native_video" -> ContentFilter.Video
                    "audio" -> ContentFilter.Audio
                    "media" -> ContentFilter.Media
                    "links" -> ContentFilter.Links
                    "replies" -> ContentFilter.Replies
                    else -> return null
                }
                return if (negated) {
                    query.copy(excludedFilters = query.excludedFilters + filter)
                } else {
                    query.copy(requiredFilters = query.requiredFilters + filter)
                }
            }

            private fun SearchQuery.withMinimum(change: Minimums.() -> Minimums) = copy(minimums = minimums.change())

            private fun String.toCount() = toIntOrNull()?.coerceAtLeast(0) ?: 0

            private fun String.toOrder() =
                when (lowercase()) {
                    "top", "score" -> SearchOrder.Top
                    "likes" -> SearchOrder.Likes
                    "replies" -> SearchOrder.Replies
                    "satszapped" -> SearchOrder.Zaps
                    else -> SearchOrder.Latest
                }

            private fun String.toTimestamp(): Long? =
                when (lowercase()) {
                    "yesterday" -> Clock.System.now().minus(1.days).epochSeconds
                    "lastweek" -> Clock.System.now().minus(7.days).epochSeconds
                    "lastmonth" -> Clock.System.now().minus(30.days).epochSeconds
                    "lastyear" -> Clock.System.now().minus(365.days).epochSeconds
                    else -> toLongOrNull() ?: isoDateToEpochSeconds()
                }

            /** `yyyy-MM-dd` as UTC midnight, so `until:` leaves that day out, as it does on X. */
            // The numbers are the calendar algorithm's own constants (days per 400-year era, month
            // offsets, ...); naming each one would only obscure a well-known published formula.
            @Suppress("MagicNumber", "ReturnCount")
            private fun String.isoDateToEpochSeconds(): Long? {
                val match = ISO_DATE.matchEntire(this) ?: return null
                val (y, m, d) = match.destructured.toList().map { it.toInt() }
                if (m !in 1..12 || d !in 1..31) return null
                // Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm).
                val year = if (m <= 2) y - 1 else y
                val era = (if (year >= 0) year else year - 399) / 400
                val yearOfEra = year - era * 400
                val dayOfYear = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
                val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
                val days = era.toLong() * 146_097 + dayOfEra - 719_468
                return days * 86_400
            }
        }
    }
}
