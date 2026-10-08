package net.primal.data.repository.feed.paging

import androidx.paging.PagingSource
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

/**
 * Routes note-feed [PagingSource] invalidation per `(ownerId, feedSpec)` instead of per table.
 *
 * `FeedPostDataCrossRef` is spec-blind: every feed's rows live in the one table, so Room's
 * table-level invalidation regenerated EVERY live feed whenever ANY feed wrote its own rows
 * (opening a profile cleared+rewrote the profile feed and thereby invalidated the home feed —
 * a full re-query, re-diff and recomposition of an identical list). The table is therefore
 * excluded from the feed's Room-observed set (see `FeedPagingSourceDaoReturnTypeConverter`),
 * and every crossref write path calls [invalidate] with its own spec after the write commits.
 *
 * Sources self-unregister via their invalidated callback, so entries never outlive the next
 * invalidation of their spec.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class FeedSpecInvalidationTracker {

    private data class FeedKey(val ownerId: String, val feedSpec: String)

    private val activeSources = AtomicReference<Map<FeedKey, Set<PagingSource<*, *>>>>(emptyMap())

    /** One retry handler per (ownerId, feedSpec) — only one mediator is ever "the" answer for a
     * given spec at a time, so a plain overwrite-on-register is correct: a new mediator
     * construction (e.g. on account switch) naturally replaces whatever handler was registered
     * before it, no unbounded growth or self-unregister dance needed here unlike [track]. */
    private val appendRetryHandlers = AtomicReference<Map<FeedKey, () -> Unit>>(emptyMap())

    /** One per (ownerId, feedSpec), overwritten on register for the same reason as
     * [appendRetryHandlers]. */
    private val feedReplacedHandlers = AtomicReference<Map<FeedKey, () -> Unit>>(emptyMap())

    fun <Key : Any, Value : Any> track(
        ownerId: String,
        feedSpec: String,
        pagingSource: PagingSource<Key, Value>,
    ): PagingSource<Key, Value> {
        val key = FeedKey(ownerId = ownerId, feedSpec = feedSpec)
        activeSources.update { current -> current + (key to (current[key].orEmpty() + pagingSource)) }
        pagingSource.registerInvalidatedCallback {
            activeSources.update { current ->
                val remaining = current[key].orEmpty() - pagingSource
                if (remaining.isEmpty()) current - key else current + (key to remaining)
            }
        }
        return pagingSource
    }

    fun invalidate(ownerId: String, feedSpec: String) {
        activeSources.load()[FeedKey(ownerId = ownerId, feedSpec = feedSpec)]
            ?.forEach { it.invalidate() }
    }

    fun invalidateAll() {
        activeSources.load().values.forEach { sources ->
            sources.forEach { it.invalidate() }
        }
    }

    /** Registers the callback that clears one mediator's own APPEND give-up state (used by
     * [retryAppend]). Called once from that mediator's own init. */
    fun registerAppendRetryHandler(ownerId: String, feedSpec: String, onRetry: () -> Unit) {
        val key = FeedKey(ownerId = ownerId, feedSpec = feedSpec)
        appendRetryHandlers.update { it + (key to onRetry) }
    }

    /** Registers what a mediator must forget when its feed is replaced (see [notifyFeedReplaced]). */
    fun registerFeedReplacedHandler(ownerId: String, feedSpec: String, onReplaced: () -> Unit) {
        val key = FeedKey(ownerId = ownerId, feedSpec = feedSpec)
        feedReplacedHandlers.update { it + (key to onReplaced) }
    }

    /**
     * The feed's rows were cleared and replaced with a fresh newest page. Called by whoever wrote
     * them, the paging mediator's own REFRESH or the view model's refresh on returning to the
     * screen, so the mediator's in-memory paging position (how far back it has already asked) is
     * reset to match the rows that now exist instead of pointing into history that was deleted.
     */
    fun notifyFeedReplaced(ownerId: String, feedSpec: String) {
        feedReplacedHandlers.load()[FeedKey(ownerId = ownerId, feedSpec = feedSpec)]?.invoke()
    }

    /**
     * Manual "load more" after a mediator has deliberately given up on APPEND. Clears that
     * mediator's own exhaustion state (so it gets a fresh retry budget, continuing further back
     * rather than repeating the window it just gave up on) then invalidates the current
     * PagingSource generation so Paging3 issues a fresh, non-terminal APPEND load. A missing
     * handler (no mediator has ever registered for this spec) still runs the invalidate — a
     * harmless no-op if nothing is tracking that spec either.
     */
    fun retryAppend(ownerId: String, feedSpec: String) {
        val key = FeedKey(ownerId = ownerId, feedSpec = feedSpec)
        appendRetryHandlers.load()[key]?.invoke()
        invalidate(ownerId = ownerId, feedSpec = feedSpec)
    }
}
