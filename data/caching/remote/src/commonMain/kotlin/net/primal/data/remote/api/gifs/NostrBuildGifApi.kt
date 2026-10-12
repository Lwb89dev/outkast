package net.primal.data.remote.api.gifs

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.primal.data.remote.api.gifs.model.GifResult
import net.primal.data.remote.api.gifs.model.GifSearchPage
import net.primal.data.remote.api.gifs.model.GifSource

/**
 * nostr.build's GIF search (`gifs.nostr.build/api/v1`, announced by nostr.build's founder on
 * 2026-09-26): over 110k GIFs taken from nostr.build's free public upload pool, so every result is
 * media that already lives on a Nostr media host and can be posted by URL as is.
 *
 * The API only answers registered clients — web clients by `Origin`, native apps by API key
 * (`Authorization: Bearer gnb_…`, created in the dashboard at gifs.nostr.build/developers once the
 * client is registered) or by a `User-Agent` an admin has reviewed. LibreNostr has a key, supplied
 * at build time; without one the request still goes out under the app's `LibreNostr/<version>`
 * User-Agent, and an unregistered answer (403 `client_not_registered`) surfaces as
 * [GifProviderException.NotRegistered], which makes [FallbackGifSearchApi] use GIFverse instead.
 *
 * Terms of use: the picker must say "GIFs from nostr.build" linked to https://nostr.build, and a
 * GIF is only ever linked where nostr.build hosts it, never uploaded again.
 *
 * There is no trending endpoint. What it does have, and what zap.observer (the first client to use
 * it) builds its picker from, is plain search plus autocomplete; [trending] is a search for a
 * Nostr staple, and the picker offers more such topics as chips.
 */
internal class NostrBuildGifApi(
    private val httpClient: HttpClient,
    /**
     * The key nostr.build issued for this app (`gnb_…`), sent as a bearer token. Native apps send no
     * `Origin`, so the key is how the API knows who is asking. Null or blank means none was
     * configured at build time, and the request goes out without it (identified by User-Agent only).
     */
    private val apiKey: String? = null,
) {

    /**
     * Everything one query has, in one request: the API's guide for grids asks for up to 200 GIFs at
     * once and not to page, since every extra request spends from a per-minute budget shared with
     * every other search and suggestion.
     */
    suspend fun search(query: String): GifSearchPage {
        val response = httpClient.get("$BASE_URL/search") {
            authorize()
            url.parameters.append("q", query.normalizedForSearch())
            url.parameters.append("limit", RESULT_LIMIT.toString())
            // The default, but spelled out: adult GIFs stay out of a picker anyone can open.
            url.parameters.append("safe", "1")
        }
        val body = response.bodyOrThrow()
        val page = json.decodeFromString<SearchResponse>(body)
        return GifSearchPage(
            results = page.items.mapNotNull { it.toGifResult() },
            source = GifSource.NostrBuild,
            nextCursor = null,
        )
    }

    suspend fun trending(): GifSearchPage = search(query = TRENDING_QUERY)

    suspend fun suggest(query: String): List<String> {
        val response = httpClient.get("$BASE_URL/suggest") {
            authorize()
            url.parameters.append("q", query.normalizedForSearch())
            url.parameters.append("limit", SUGGESTION_LIMIT.toString())
            url.parameters.append("safe", "1")
        }
        val body = response.bodyOrThrow()
        return json.decodeFromString<SuggestResponse>(body).terms
            .mapNotNull { it.term?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
    }

    private fun HttpRequestBuilder.authorize() {
        apiKey?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun HttpResponse.bodyOrThrow(): String {
        val body = bodyAsText()
        if (status.isSuccess()) return body
        throw when {
            status == HttpStatusCode.Forbidden && NOT_REGISTERED_CODE in body ->
                GifProviderException.NotRegistered(provider = GifSource.NostrBuild)
            status == HttpStatusCode.TooManyRequests ->
                GifProviderException.RateLimited(
                    provider = GifSource.NostrBuild,
                    retryAfterSeconds = headers[HttpHeaders.RetryAfter]?.trim()?.toIntOrNull(),
                )
            else -> GifProviderException.Unavailable(provider = GifSource.NostrBuild, status = status.value)
        }
    }

    /**
     * How the API wants a query: trimmed, runs of spaces collapsed, lower case, and at most 500
     * characters (longer is a 400), cut between code points so an emoji is never split in half.
     */
    private fun String.normalizedForSearch(): String {
        val collapsed = trim().replace(WHITESPACE_RUN, " ").lowercase()
        if (collapsed.length <= MAX_QUERY_LENGTH) return collapsed
        val cut = if (collapsed[MAX_QUERY_LENGTH - 1].isHighSurrogate()) MAX_QUERY_LENGTH - 1 else MAX_QUERY_LENGTH
        return collapsed.take(cut)
    }

    /**
     * The smallest animated preview that fills a picker cell: `w240` (at most 240 px wide) is what
     * the API documents for column grids on phone-sized screens. Its `animated` member is null when
     * the GIF is too large to animate, and then only its first frame (`still`) exists.
     */
    private fun Gif.toGifResult(): GifResult? {
        val mimeType = MIME_TYPES[format]
        val preview = previews?.w240 ?: previews?.medium
        val previewUrl = preview?.animated?.takeIf { it.isNotBlank() } ?: preview?.still?.takeIf { it.isNotBlank() }
        val incomplete = id.isNullOrBlank() || url.isNullOrBlank() || mimeType == null || previewUrl == null
        if (incomplete || width <= 0 || height <= 0) return null
        return GifResult(
            id = id,
            url = url,
            previewUrl = previewUrl,
            mimeType = mimeType,
            width = width,
            height = height,
            sizeBytes = bytes?.takeIf { it > 0 },
            title = title.orEmpty(),
        )
    }

    @Serializable
    private data class SearchResponse(
        val count: Int = 0,
        val offset: Int = 0,
        val items: List<Gif> = emptyList(),
    )

    @Serializable
    private data class Gif(
        val id: String? = null,
        val url: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val bytes: Long? = null,
        val format: String? = null,
        val title: String? = null,
        val previews: Previews? = null,
    )

    @Serializable
    private data class Previews(
        val medium: Preview? = null,
        val w240: Preview? = null,
    )

    @Serializable
    private data class Preview(
        val animated: String? = null,
        val still: String? = null,
    )

    @Serializable
    private data class SuggestResponse(
        val terms: List<Term> = emptyList(),
    )

    @Serializable
    private data class Term(
        val term: String? = null,
    )

    private companion object {
        const val BASE_URL = "https://gifs.nostr.build/api/v1"
        const val NOT_REGISTERED_CODE = "client_not_registered"
        const val TRENDING_QUERY = "gm"
        const val RESULT_LIMIT = 200
        const val MAX_QUERY_LENGTH = 500
        val WHITESPACE_RUN = Regex("\\s+")
        const val SUGGESTION_LIMIT = 8

        /** The two formats the index serves; anything else is skipped rather than guessed at. */
        val MIME_TYPES = mapOf("gif" to "image/gif", "webp" to "image/webp")

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}
