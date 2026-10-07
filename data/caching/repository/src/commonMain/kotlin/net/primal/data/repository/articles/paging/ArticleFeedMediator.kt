package net.primal.data.repository.articles.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.github.aakira.napier.Napier
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withContext
import net.primal.core.caching.MediaCacher
import net.primal.core.networking.utils.orderByPagingIfNotNull
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.notes.FeedPostRemoteKey
import net.primal.data.local.dao.reads.ArticleFeedCrossRef
import net.primal.data.local.dao.reads.ArticleFeedItem
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.articles.model.ArticleResponse
import net.primal.data.repository.articles.processors.persistToDatabase
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.data.repository.fetch.RelaysUnreachableException
import net.primal.data.repository.mappers.remote.mapNotNullAsArticleDataPO
import net.primal.data.repository.utils.cacheAvatarUrls
import net.primal.domain.common.ContentPrimalPaging
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.shared.data.local.db.withTransaction

@ExperimentalPagingApi
internal class ArticleFeedMediator(
    private val userId: String,
    private val feedSpec: String,
    private val database: CachingDatabase,
    private val dispatcherProvider: DispatcherProvider,
    private val mediaCacher: MediaCacher? = null,
    private val relayEventQuerier: RelayEventQuerier,
    private val fetchCoordinator: FetchCoordinator,
) : RemoteMediator<Int, ArticleFeedItem>() {

    private val lastRequests: MutableMap<LoadType, Long> = mutableMapOf()

    /** The boundary the last APPEND asked for, so only a repeat of that same question is throttled. */
    private var lastAppendUntil: Long? = null
    private val relayFetcher = RelayArticleFeedFetcher(
        querier = relayEventQuerier,
        coordinator = fetchCoordinator,
    )

    override suspend fun initialize(): InitializeAction {
        val latestRemoteKey = withContext(dispatcherProvider.io()) {
            database.feedPostsRemoteKeys().findLatestByDirective(ownerId = userId, directive = feedSpec)
        }

        return latestRemoteKey?.let {
            if (it.cachedAt.isTimestampOlderThan(duration = INITIALIZE_CACHE_EXPIRY)) {
                InitializeAction.LAUNCH_INITIAL_REFRESH
            } else {
                InitializeAction.SKIP_INITIAL_REFRESH
            }
        } ?: InitializeAction.LAUNCH_INITIAL_REFRESH
    }

    @Suppress("ReturnCount")
    override suspend fun load(loadType: LoadType, state: PagingState<Int, ArticleFeedItem>): MediatorResult {
        val nextUntil = when (loadType) {
            LoadType.APPEND -> findLastRemoteKey(state = state)?.sinceId
                ?: run {
                    Napier.d("APPEND no remote key found exit.")
                    return MediatorResult.Success(endOfPaginationReached = true)
                }

            LoadType.PREPEND -> {
                Napier.d("PREPEND end of pagination exit.")
                return MediatorResult.Success(endOfPaginationReached = true)
            }

            LoadType.REFRESH -> null
        }

        return try {
            val response = fetchArticles(
                pageSize = state.config.pageSize,
                nextUntil = nextUntil,
                loadType = loadType,
            )

            // A refresh that found no articles is far more likely a relay pool that is still
            // connecting, or relays that did not answer in time, than a follow list that has
            // never published a long-form article. Replacing the cached feed with nothing used to
            // wipe the Reads tab on every such refresh, one that runs whenever the cache is more
            // than a few minutes old. The note feed already refuses to clear for an empty page.
            val replaceCachedFeed = loadType == LoadType.REFRESH && response.articles.isNotEmpty()
            processAndPersistToDatabase(response = response, clearFeed = replaceCachedFeed)

            MediatorResult.Success(endOfPaginationReached = false)
        } catch (error: NetworkException) {
            MediatorResult.Error(error)
        } catch (_: RepeatingRequestBodyException) {
            // The same boundary was asked for a moment ago. Skip this call; do not end the list.
            // Ending it was permanent for this PagingSource, so two scrolls within ten seconds
            // used to be enough to stop the Reads feed from ever loading another page.
            Napier.d("Repeated APPEND skipped.")
            MediatorResult.Success(endOfPaginationReached = false)
        } catch (_: RelaysUnreachableException) {
            // No relay answered, which says nothing about older articles. Leave the cursor alone
            // so the next scroll (or refresh) asks again.
            Napier.d("No relay answered the Reads request.")
            MediatorResult.Success(endOfPaginationReached = false)
        }
    }

    @Throws(RepeatingRequestBodyException::class, CancellationException::class)
    private suspend fun fetchArticles(
        pageSize: Int,
        nextUntil: Long?,
        loadType: LoadType,
    ): ArticleResponse {
        val lastRequestAt = lastRequests[loadType]
        val isRepeat = loadType == LoadType.APPEND &&
            nextUntil == lastAppendUntil &&
            lastRequestAt != null &&
            !lastRequestAt.isRequestCacheExpired()
        if (isRepeat) throw RepeatingRequestBodyException()

        val response = withContext(dispatcherProvider.io()) {
            relayFetcher.fetch(
                userId = userId,
                feedSpec = feedSpec,
                limit = pageSize,
                until = nextUntil,
            )
        }
        mediaCacher?.cacheAvatarUrls(metadata = response.metadata, cdnResources = response.cdnResources)
        lastRequests[loadType] = Clock.System.now().epochSeconds
        if (loadType == LoadType.APPEND) lastAppendUntil = nextUntil
        return response
    }

    private suspend fun findLastRemoteKey(state: PagingState<Int, ArticleFeedItem>): FeedPostRemoteKey? {
        val lastItemATag = state.lastItemOrNull()?.data?.aTag
            ?: findLastItemOrNull()?.articleATag

        return withContext(dispatcherProvider.io()) {
            lastItemATag?.let { database.feedPostsRemoteKeys().findByEventId(ownerId = userId, eventId = lastItemATag) }
                ?: database.feedPostsRemoteKeys().findLatestByDirective(ownerId = userId, directive = feedSpec)
        }
    }

    private suspend fun findLastItemOrNull(): ArticleFeedCrossRef? =
        withContext(dispatcherProvider.io()) {
            database.articleFeedsConnections().findLastBySpec(ownerId = userId, spec = feedSpec)
        }

    private suspend fun processAndPersistToDatabase(response: ArticleResponse, clearFeed: Boolean) {
        val connections = response.articles
            .orderByPagingIfNotNull(pagingEvent = response.paging)
            .mapNotNullAsArticleDataPO(cdnResources = emptyList()).map {
                ArticleFeedCrossRef(
                    ownerId = userId,
                    spec = feedSpec,
                    articleATag = it.aTag,
                    articleAuthorId = it.authorId,
                )
            }

        withContext(dispatcherProvider.io()) {
            database.withTransaction {
                if (clearFeed) {
                    database.feedPostsRemoteKeys().deleteByDirective(ownerId = userId, directive = feedSpec)
                    database.articleFeedsConnections().deleteConnectionsBySpec(ownerId = userId, spec = feedSpec)
                }

                database.articleFeedsConnections().connect(data = connections)

                response.persistToDatabase(
                    userId = userId,
                    database = database,
                )
            }

            connections.processRemoteKeys(pagingEvent = response.paging)
        }
    }

    private suspend fun List<ArticleFeedCrossRef>.processRemoteKeys(pagingEvent: ContentPrimalPaging?) {
        val sinceId = pagingEvent?.sinceId
        val untilId = pagingEvent?.untilId
        if (sinceId != null && untilId != null) {
            val remoteKeys = this.map {
                FeedPostRemoteKey(
                    ownerId = userId,
                    eventId = it.articleATag,
                    directive = feedSpec,
                    sinceId = sinceId,
                    untilId = untilId,
                    cachedAt = Clock.System.now().epochSeconds,
                )
            }
            database.feedPostsRemoteKeys().upsert(remoteKeys)
        }
    }

    private fun Long.isTimestampOlderThan(duration: Long) = (Clock.System.now().epochSeconds - this) > duration

    private fun Long.isRequestCacheExpired() = isTimestampOlderThan(duration = LAST_REQUEST_EXPIRY)

    private inner class RepeatingRequestBodyException : RuntimeException()

    companion object {
        private val LAST_REQUEST_EXPIRY = 10.seconds.inWholeSeconds
        private val INITIALIZE_CACHE_EXPIRY = 3.minutes.inWholeSeconds
    }
}
